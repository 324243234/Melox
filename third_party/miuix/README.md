# Miuix Native Rail Inset Patch

Melox uses a local Android UI patch based on official Miuix `0.9.4`.
`0.9.4.1-melox` is a Melox patch version, not an upstream release. Other Miuix
modules and the UI artifact's transitive dependencies remain at their original
versions.

- Upstream: [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)
- Base commit: `39c40f99844227b853f0049a0933b1f3ae6c00ba` (`v0.9.4`)
- License: Apache-2.0; see [LICENSE](LICENSE)
- Source changes: [navigation-rail-insets.patch](navigation-rail-insets.patch)
- Original dependency metadata: [upstream-ui-android.pom](upstream-ui-android.pom)
- Bundled AAR SHA-256:
  `25b6dff240bfef7e25e6559286e262cfca5905e7e779f699dfd890093ccf3a95`

The new stateful `NavigationRail` overload requires `statusBarInsets` and accepts
optional `contentBottomInsets`. The top input replaces the existing inner
content column's top inset. The bottom input reserves and consumes viewport
space before scrolling, avoiding duplicate native navigation-bar spacing. Both
apply when default inset handling is enabled. Existing overloads keep their
original JVM signatures and behavior. The native background, divider, expand
toggle and start-cutout handling are unchanged. Melox passes
`WindowInsets.statusBarsIgnoringVisibility` and a bottom inset ending at the
mini-player surface top; it adds no outer rail padding/background.

The source patch also documents the API in English/Chinese and exercises the
new overload in upstream demo/example code with the original inset behavior.

## Rebuild

Requirements: Python 3, Git, JDK 21 and the Android SDK/toolchain configured for
Melox. Use an upstream clone containing the pinned commit:

```sh
python3 third_party/miuix/rebuild.py /path/to/miuix
```

The script reads that clone without changing it, exports the pinned source to
a temporary directory, verifies the original rail source hash, applies the
patch, builds the Android AAR and updates this directory's local Maven artifact.
It copies only `sdk.dir` from Melox's local configuration when present. The
isolated build omits demo/baseline-profile projects and their profile-generation
dependency; it does not alter library implementation or dependency versions.
No remote publication is performed. Ordinary Melox builds consume the bundled
AAR and do not require the upstream checkout or a rebuild.

## Verification

All five original public method descriptors in `NavigationRailKt` are retained.
The patched source's native implementation body differs only in the inner top
inset input and the optional bottom viewport inset. The full source rebuild
leaves every non-rail class identical to
the official AAR. Rendering regressions live in Melox's
`NavigationRailInsetsRenderingTest`; device execution is required to establish
actual status-bar and visual behavior.
