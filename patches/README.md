# Android changes over the pinned upstream

`scripts/patch-upstream.mjs` checks expected source anchors and applies these changes before compilation:

- Encrypt the credentials provider’s existing document IO with AES-256-GCM. Keep its existing parse, locking and atomic replacement protocol. Android Keystore wraps a random per-installation data key. No plaintext credentials document is written.
- Refuse subprocess and terminal creation on Android until command permission is enabled in the carrier. This is an explicit application-level permission, not kernel workspace confinement.
- Include the mobile stylesheet in the upstream HTML build. Android window insets and keyboard space are managed by the carrier.
- Use the carrier-selected private project for first-use workspace selection, including after the user changes projects in the Android menu. Preserve upstream workspace/session identities and data formats.
- Provide Android POSIX flock loading from APK-native libraries, Android terminal process inspection, and the packaged Bash path. Access Node's internal resolver through the carrier's `--expose-internals` launch option, without depending on an unavailable desktop native loader.
- Use a narrow-screen drawer over the content area and share the carrier's launch token with upstream browser authentication. Upstream cookie exchange and RPC trust checks remain active.

The runtime overlay selects the in-page directory picker, removes desktop “Open In” integration, and keeps upstream per-tool approval. The carrier preload authenticates HTTP and WebSocket traffic and redirects native-addon loading to installed APK libraries.

These changes do not alter the upstream session format. Any future baseline update must reapply and retest the patch contracts; patch failures stop packaging.

- Parse internal resource authorities on WebView versions that expose custom-scheme authorities as opaque paths; retain the protocol allowlist and host file-access checks.
