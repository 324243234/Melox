# Detached-owner cleanup patch for Compose UI

This is a Melox-local build of `androidx.compose.ui:ui-android:1.12.0`,
published only in this repository as `1.12.0-melox`. It is not an official
AndroidX release. Gradle substitutes only this Android artifact; other Compose
modules and their versions are unchanged.

## Reason and scope

The supplied release crash retraces to lazy-list prefetch cancellation disposing
a precomposed slot after its old layout root has lost its owner. The private
`LayoutNodeSubcompositionsState.outOfFrameExecutor` getter calls `requireOwner()`
even though its result is nullable and the caller already supports synchronous
cleanup when no executor is available.

The sole behavior change is equivalent to this source change in
`commonMain/androidx/compose/ui/layout/SubcomposeLayout.kt`, line 664:

```diff
-        get() = root.requireOwner().outOfFrameExecutor
+        get() = root.owner?.outOfFrameExecutor
```

An attached root still forwards the same owner's executor. A detached root
returns null, selecting the existing synchronous composition-deactivation path.
Normal prefetch remains enabled. No production reflection, global exception
handler, list-state replacement, or other framework changes are introduced.

## Provenance and license

- [Official upstream AAR](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.0/ui-android-1.12.0.aar)
- [Official upstream POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.0/ui-android-1.12.0.pom)
- [Official upstream sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.0/ui-android-1.12.0-sources.jar)

AndroidX is authored by the Android Open Source Project and licensed under
Apache-2.0; see `LICENSE`. Original AAR/JAR entries, including metadata and
notices, are preserved. `upstream.json` pins the original AAR, POM and target
class hashes. `subcompose-detached-owner.patch` records the source equivalent;
`PatchSubcomposeOwner.java` performs the narrowly scoped bytecode transformation.

## Rebuild and validation

Use JDK 21, Python 3, and ASM/ASM Tree 9.9. Supply the original, unmodified AAR
and POM, plus the two ASM JARs:

```sh
python3 third_party/compose-ui/rebuild.py \
  --aar /path/to/ui-android-1.12.0.aar \
  --pom third_party/compose-ui/upstream-ui-android.pom \
  --asm /path/to/asm-9.9.jar \
  --asm-tree /path/to/asm-tree-9.9.jar \
  --java-home /path/to/jdk-21
```

The script rejects unexpected upstream hashes, requires exactly one original
`requireOwner` call in the target getter, and verifies that every other JAR
entry is byte-identical. It also compares disassembled method bodies within
the changed class: only the getter may differ. The original POM's dependency
versions remain intact; only the local artifact version and unavailable Gradle
metadata marker change.

The generated AAR SHA-256 is
`247bcd9e9444e0a095fcd74e875c855692f9106bfe0c2276ff508ed7f62e3def`.
`verification.json` records the checks. The local Maven artifact is bundled
as an input to ordinary app builds; ASM is not an app dependency.

`SubcomposeLayoutOwnerTest` exercises the actual framework getter for detached,
attached and subsequently detached owners. Test-only constructor bypass avoids
Android drawing stubs. The original AAR fails the two detached cases with the
reported exception and passes the attached case. This isolates the reported
failure without claiming device-level navigation or rendering coverage.
The patched artifact passes all three cases, including the unchanged
attached-owner control.

Build, test, dependency-selection and APK evidence is retained in
`artifacts/prefetch-crashes-2026-10-18/`. The user's current instruction permits
package delivery only; no emulator or device execution is part of this repair.
