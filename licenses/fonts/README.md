# Bundled font

Noto Sans SC, unmodified variable TTF, from the Google Fonts repository:

- Source commit: `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0`
- Path: `ofl/notosanssc/NotoSansSC[wght].ttf`
- SHA-256: `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`
- License: SIL Open Font License 1.1, reproduced in `NotoSansSC-OFL.txt`.

The font is embedded without renaming or modification. OFL permits embedding
and commercial use; it is not sold separately. System default and system
monospace use fonts supplied by the Android device, with CJK fallback.

Google Material Color Utilities 0.3.0 (Apache-2.0) generates the checked-in
Material light/dark schemes. `npm ci && node scripts/generate-colors.mjs`
reproduces `MaterialColors.kt`; it is a development tool, not an APK dependency.
