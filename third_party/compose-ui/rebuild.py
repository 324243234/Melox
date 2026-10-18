#!/usr/bin/env python3
"""Apply the pinned detached-owner getter fix without rebuilding other UI classes."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
TARGET = "androidx/compose/ui/layout/LayoutNodeSubcompositionsState.class"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def repack(original, replacements):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(output, "w") as target:
        target.comment = source.comment
        for entry in source.infolist():
            target.writestr(entry, replacements.get(entry.filename, source.read(entry.filename)))
    return output.getvalue()


def disassembly(javap, jar):
    return subprocess.check_output([str(javap), "-p", "-c", "-classpath", str(jar),
                                    "androidx.compose.ui.layout.LayoutNodeSubcompositionsState"], text=True)


def without_getter(text):
    return re.sub(r"  private final androidx\.compose\.ui\.node\.OutOfFrameExecutor getOutOfFrameExecutor\(\);\n.*?\n\n",
                  "", text, count=1, flags=re.S)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("aar", "pom", "asm", "asm-tree", "java-home"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    metadata = json.loads((ROOT / "upstream.json").read_text())
    original_aar = args.aar.read_bytes()
    original_pom = args.pom.read_bytes()
    assert sha256(original_aar) == metadata["aarSha256"], "Unexpected upstream AAR"
    assert sha256(original_pom) == metadata["pomSha256"], "Unexpected upstream POM"
    with zipfile.ZipFile(io.BytesIO(original_aar)) as archive:
        original_jar = archive.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(original_jar)) as archive:
        original_class = archive.read(TARGET)
    assert sha256(original_class) == metadata["targetClassSha256"], "Unexpected target class"
    tools = args.java_home / "bin"
    asm_classpath = os.pathsep.join(map(str, (args.asm, args.asm_tree)))
    with tempfile.TemporaryDirectory(prefix="melox-compose-owner-") as temporary:
        work = Path(temporary)
        subprocess.run([str(tools / "javac"), "--release", "21", "-cp", asm_classpath,
                        "-d", str(work), str(ROOT / "PatchSubcomposeOwner.java")], check=True)
        old_class, new_class = work / "original.class", work / "patched.class"
        old_class.write_bytes(original_class)
        subprocess.run([str(tools / "java"), "-cp", str(work) + os.pathsep + asm_classpath,
                        "PatchSubcomposeOwner", str(old_class), str(new_class)], check=True)
        patched_jar = repack(original_jar, {TARGET: new_class.read_bytes()})
        with zipfile.ZipFile(io.BytesIO(original_jar)) as old, zipfile.ZipFile(io.BytesIO(patched_jar)) as new:
            assert old.namelist() == new.namelist()
            changed = [name for name in old.namelist() if old.read(name) != new.read(name)]
        assert changed == [TARGET], changed
        old_jar, new_jar = work / "original.jar", work / "patched.jar"
        old_jar.write_bytes(original_jar)
        new_jar.write_bytes(patched_jar)
        old_code, new_code = disassembly(tools / "javap", old_jar), disassembly(tools / "javap", new_jar)
        assert old_code != new_code
        assert without_getter(old_code) == without_getter(new_code), "Other methods changed"
    patched_aar = repack(original_aar, {"classes.jar": patched_jar})
    with zipfile.ZipFile(io.BytesIO(original_aar)) as old, zipfile.ZipFile(io.BytesIO(patched_aar)) as new:
        assert old.namelist() == new.namelist()
        assert [name for name in old.namelist() if old.read(name) != new.read(name)] == ["classes.jar"]
    version = metadata["patchVersion"]
    repo = ROOT / "maven/androidx/compose/ui/ui-android" / version
    repo.mkdir(parents=True, exist_ok=True)
    (repo / ("ui-android-" + version + ".aar")).write_bytes(patched_aar)
    pom_text = original_pom.decode().replace("<version>" + metadata["baseVersion"] + "</version>",
                                             "<version>" + version + "</version>", 1)
    pom_text = pom_text.replace("<!-- do_not_remove: published-with-gradle-metadata -->",
                                "<!-- Melox local patch is described by this POM. -->")
    (repo / ("ui-android-" + version + ".pom")).write_text(pom_text)
    result = {"baseVersion": metadata["baseVersion"], "patchVersion": version,
              "changedClasses": changed, "allOtherAarEntriesUnchanged": True, "allOtherClassesUnchanged": True,
              "allOtherMethodsUnchanged": True, "aarSha256": sha256(patched_aar)}
    (ROOT / "verification.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
