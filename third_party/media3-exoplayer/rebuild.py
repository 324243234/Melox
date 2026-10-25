#!/usr/bin/env python3
"""Rebuild the pinned Media3 float-playback patch, preserving every other class."""
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
TARGET = "androidx/media3/exoplayer/audio/DefaultAudioSink.class"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def repack(data, replacements):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(result, "w") as target:
        target.comment = source.comment
        for entry in source.infolist():
            target.writestr(entry, replacements.get(entry.filename, source.read(entry.filename)))
    return result.getvalue()


def methods(text):
    parts = re.split(r"(?m)^(  (?:public|private|protected).+\).*;)$", text)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts) - 1, 2)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("aar", "pom", "asm", "asm-tree", "java-home"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    metadata = json.loads((ROOT / "upstream.json").read_text())
    aar, pom = args.aar.read_bytes(), args.pom.read_bytes()
    assert digest(aar) == metadata["aarSha256"], "Unexpected AAR"
    assert digest(pom) == metadata["pomSha256"], "Unexpected POM"
    with zipfile.ZipFile(io.BytesIO(aar)) as archive:
        jar = archive.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        original = archive.read(TARGET)
    assert digest(original) == metadata["targetClassSha256"], "Unexpected sink class"
    runtime = args.java_home / "bin"
    classpath = os.pathsep.join(map(str, (args.asm, args.asm_tree)))
    with tempfile.TemporaryDirectory(prefix="melox-float-playback-") as directory:
        work = Path(directory)
        subprocess.run([str(runtime / "javac"), "--release", "21", "-cp", classpath,
                        "-d", str(work), str(ROOT / "PatchFloatPlayback.java")], check=True)
        old_class, new_class = work / "original.class", work / "patched.class"
        old_class.write_bytes(original)
        subprocess.run([str(runtime / "java"), "-cp", str(work) + os.pathsep + classpath,
                        "PatchFloatPlayback", str(old_class), str(new_class)], check=True)
        patched_jar = repack(jar, {TARGET: new_class.read_bytes()})
        with zipfile.ZipFile(io.BytesIO(jar)) as old, zipfile.ZipFile(io.BytesIO(patched_jar)) as new:
            assert old.namelist() == new.namelist()
            assert [n for n in old.namelist() if old.read(n) != new.read(n)] == [TARGET]
        old_jar, new_jar = work / "original.jar", work / "patched.jar"
        old_jar.write_bytes(jar)
        new_jar.write_bytes(patched_jar)
        def code(path):
            return subprocess.check_output([str(runtime / "javap"), "-p", "-c", "-classpath",
                                            str(path), TARGET[:-6].replace("/", ".")], text=True)
        old_methods, new_methods = methods(code(old_jar)), methods(code(new_jar))
        assert old_methods.keys() == new_methods.keys()
        changed = [name for name in old_methods if old_methods[name] != new_methods[name]]
        assert changed == [
            "  public void configure(androidx.media3.exoplayer.audio.AudioSink$AudioSinkConfig) throws androidx.media3.exoplayer.audio.AudioSink$ConfigurationException;",
            "  private boolean shouldApplyAudioProcessorPlaybackParameters();",
        ], changed
    patched_aar = repack(aar, {"classes.jar": patched_jar})
    with zipfile.ZipFile(io.BytesIO(aar)) as old, zipfile.ZipFile(io.BytesIO(patched_aar)) as new:
        assert old.namelist() == new.namelist()
        assert [n for n in old.namelist() if old.read(n) != new.read(n)] == ["classes.jar"]
    version = metadata["patchVersion"]
    destination = ROOT / "maven/androidx/media3/media3-exoplayer" / version
    destination.mkdir(parents=True, exist_ok=True)
    (destination / f"media3-exoplayer-{version}.aar").write_bytes(patched_aar)
    text = pom.decode().replace(f'<version>{metadata["baseVersion"]}</version>', f"<version>{version}</version>", 1)
    text = text.replace("<!-- do_not_remove: published-with-gradle-metadata -->",
                        "<!-- Melox local float-playback patch is described by this POM. -->")
    (destination / f"media3-exoplayer-{version}.pom").write_text(text)
    verification = {"changedClasses": [TARGET], "changedMethods": changed,
                    "allOtherClassesAndMethodsUnchanged": True,
                    "allOtherAarEntriesUnchanged": True, "aarSha256": digest(patched_aar)}
    (ROOT / "verification.json").write_text(json.dumps(verification, indent=2) + "\n")
    print(json.dumps(verification, indent=2))


if __name__ == "__main__":
    main()
