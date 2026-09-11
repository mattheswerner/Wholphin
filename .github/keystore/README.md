# Debug signing key for the test builds of this branch

`debug.keystore` is a throwaway Android **debug** key, with the standard debug credentials
(store and key password `android`, alias `androiddebugkey`). It is checked in on purpose.

A GitHub Actions runner starts without a debug keystore, so Gradle generates one for the build
and throws it away again. Every APK then carries a different signature, and Android refuses to
install one over another, which makes testing successive builds on a device needlessly painful.
Keeping one key here means every build of this branch installs over the last.

It signs debug builds only. Release builds use the project's own signing configuration and are
untouched by this. Do not reuse this key for anything that matters: its private key is public,
exactly like the debug key the Android SDK ships to everybody.

Remove this directory along with `dovi-build.yml` before proposing the branch upstream.
