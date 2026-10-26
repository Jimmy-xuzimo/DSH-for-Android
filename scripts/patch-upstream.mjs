import { readFile, writeFile, copyFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(process.argv[2] ?? 'runtime/upstream');
const port = fileURLToPath(new URL('..', import.meta.url));
async function patch(relative, transform) {
  const filename = join(root, relative);
  const original = await readFile(filename, 'utf8');
  if (original.includes('DSH_ANDROID_PATCH')) return;
  const modified = transform(original);
  if (modified === original) throw new Error(`Upstream patch no longer applies: ${relative}`);
  await writeFile(filename, '// DSH_ANDROID_PATCH: see Android port patches/README.md\n' + modified);
}
await patch('packages/credentials/credentials-local/src/index.ts', source => {
  const reads = source.match(/await readFile\(this\.spec\.filename, 'utf8'\)/g) ?? [];
  const writes = source.match(/writeFileAtomic\(this\.spec\.filename, (nextText|migrated),/g) ?? [];
  if (reads.length !== 3 || writes.length !== 4) throw new Error('Credential IO contract changed');
  return "import { encryptCredentials, decryptCredentials } from './android-crypto.ts'\n" + source
    .replaceAll("await readFile(this.spec.filename, 'utf8')", "decryptCredentials(await readFile(this.spec.filename, 'utf8'))")
    .replace(/writeFileAtomic\(this\.spec\.filename, (nextText|migrated),/g, 'writeFileAtomic(this.spec.filename, encryptCredentials($1),');
});
await copyFile(join(port, 'patches/credential-crypto.ts'), join(root, 'packages/credentials/credentials-local/src/android-crypto.ts'));
for (const relative of ['packages/session/session-persistence-jsonl/src/index.ts', 'packages/attachment/attachment-local/src/store.ts']) {
  await patch(relative, source => {
    const modified = source.replace(/(?<=import \{[^\n]*)\blink, /, '');
    if (modified === source) throw new Error(`Atomic publication contract changed: ${relative}`);
    return "import { link } from './android-atomic-publish.ts'\n" + modified;
  });
  await copyFile(join(port, 'patches/atomic-publish.ts'), join(root, relative.replace(/[^/]+$/, 'android-atomic-publish.ts')));
}
await patch('native/system/packages/entry/src/flock.ts', source => {
  const anchor = '  const { platform, arch } = process';
  if (!source.includes(anchor)) throw new Error('Native flock resolver changed');
  return source.replace(anchor, anchor + `
  if (platform === 'android') {
    binding = createRequire(import.meta.url)('./android-system.node') as FlockBinding
    return binding
  }`);
});
await patch('packages/subprocess/subprocess-local/src/process-inspector.ts', source =>
  source.replace("if (platform === 'linux') return new LinuxProcessInspector(arch, internals)",
    "if (platform === 'linux' || platform === 'android') return new LinuxProcessInspector(arch, internals)"));
await patch('packages/terminal/terminal-bash/src/config.ts', source =>
  source.replace("export const DEFAULT_BASH_SHELL = '/bin/bash'",
    "export const DEFAULT_BASH_SHELL = process.platform === 'android' ? process.env.SHELL ?? '/system/bin/sh' : '/bin/bash'"));
await patch('packages/fs/tool-fs-search/src/search-core.ts', source => {
  const anchor = '  rgPathPromise ??= Promise.resolve().then(async () => {';
  if (!source.includes(anchor)) throw new Error('Search runtime contract changed');
  return source.replace(anchor, anchor + `
    if (process.platform === 'android') {
      const directory = process.env.DSH_ANDROID_NATIVE_DIR
      if (directory === undefined) throw new Error('Android native directory is missing')
      return join(directory, 'libdsh_rg.so')
    }`);
});
await patch('packages/api/session-controller/src/client/time-zone.ts', source => {
  const anchor = '  return timeZone';
  if (!source.includes(anchor)) throw new Error('Client time-zone contract changed');
  // Chromium on Android may report zero-offset UTC as an ISO offset instead
  // of the IANA name required by the server. Preserve all actual named zones.
  return source.replace(anchor, "  return /^(?:[+-]00:00|GMT|Etc\\/UTC|Etc\\/GMT)$/.test(timeZone) ? 'UTC' : timeZone");
});
await patch('packages/client/resources/src/client/resources.ts', source => {
  const anchor = "  return parsed.hostname === '' ? undefined : parsed.hostname.toLowerCase()";
  if (!source.includes(anchor)) throw new Error('Client resource protocol contract changed');
  // WebView before Chromium 130 leaves non-special URL authorities in the
  // opaque pathname. Accept only a literal, ASCII resource type in that case.
  return source.replace(anchor, `  if (parsed.hostname !== '') return parsed.hostname.toLowerCase()
  return /^dsh-resource:\\/\\/([a-z0-9][a-z0-9.-]*)(?:[/?#]|$)/i.exec(address)?.[1]?.toLowerCase()`);
});
await patch('packages/boot/app-boot/src/profile-resolution/resolver.ts', source => {
  const anchor = "const addon = require('node-addon-require-builtin') as { requireBuiltin(moduleId: string): unknown }";
  if (!source.includes(anchor)) throw new Error('Upstream internal-module access contract changed');
  return source.replace(anchor, "const addon = process.platform === 'android'\n    ? { requireBuiltin: (moduleId: string): unknown => require(moduleId) }\n    : require('node-addon-require-builtin') as { requireBuiltin(moduleId: string): unknown }");
});
await patch('packages/client/connection/src/browser-auth.ts', source => {
  const anchor = 'const created = encodeBase64Url(randomBytes(SECRET_BYTES))';
  if (!source.includes(anchor)) throw new Error('Upstream browser-token contract changed');
  return source.replace(anchor, "const created = process.platform === 'android' ? process.env.DSH_ANDROID_TOKEN : encodeBase64Url(randomBytes(SECRET_BYTES))\n  if (created === undefined) throw new Error('Android carrier token is required')");
});
await patch('packages/client/ui-layout/src/client/AppFrame.tsx', source => {
  const rootMarker = 'data-sidebar-collapsed={sidebarCollapsed || undefined}';
  const centerMarker = '      <>\n        <CenterColumn>';
  if (!source.includes(rootMarker) || !source.includes(centerMarker)) throw new Error('Upstream frame contract changed');
  return source.replace(rootMarker, 'data-android-compact={viewport < 600 || undefined}\n      ' + rootMarker)
    .replace('<div className={css.sidebarCol}>', '<div className={css.sidebarCol} data-android-sidebar>')
    .replace(centerMarker, `      {viewport < 600 && !sidebarCollapsed && (
        <button type="button" data-android-drawer-backdrop
          aria-label={document.documentElement.lang.startsWith('zh') ? '关闭侧栏' : 'Close sidebar'}
          onClick={() => actions.toggleSidebar()} />
      )}
` + centerMarker);
});
await patch('packages/api/workspace-controller/src/index.ts', source => {
  const anchor = 'async initializeDefault(signal: AbortSignal): Promise<WorkspaceValue | undefined> {';
  if (!source.includes(anchor)) throw new Error('Workspace initialization contract changed');
  return source.replace(anchor, anchor + `
    if (process.platform === 'android') {
      signal.throwIfAborted()
      const { workspace } = await this.commands.create({ path: process.cwd() })
      return { workspace }
    }`);
});
await patch('packages/client/ui-workspace/src/client/navigation.ts', source => {
  const anchor = 'if (target === undefined && workspaces.items.length === 0 && sessions.ids.length === 0) {';
  if (!source.includes(anchor)) throw new Error('Workspace navigation contract changed');
  return source.replace(anchor, "if (document.documentElement.dataset.android === 'true' || (target === undefined && workspaces.items.length === 0 && sessions.ids.length === 0)) {")
    .replace('      target = prepared?.workspaceId', "      if (document.documentElement.dataset.android === 'true' && target !== prepared?.workspaceId) sessionId = undefined\n      target = prepared?.workspaceId");
});
await patch('packages/boot/plugin-manager/src/index.ts', source => {
  const boundary = '\n    return bundles\n';
  const requested = 'const known = specPackageName(spec, before, this.profile.dir)';
  if (!source.includes(boundary) || !source.includes(requested)) throw new Error('Plugin inventory contract changed');
  const reason = 'Android 不支持桌面浏览器和电脑控制扩展。Desktop-only capability is unavailable on Android.';
  const helper = `
function androidUnsupportedBundle(name: string | undefined): boolean {
  return process.platform === 'android' && name !== undefined &&
    /^@deepseek-ai\\/dsh-(?:cua-bundle|browser-use|computer-use|experimental-(?:browser-use|computer-use|cua-))/.test(name)
}
`;
  return source.replace(boundary, `
    return bundles.map(bundle => {
      if (!androidUnsupportedBundle(bundle.name)) return bundle
      const unavailable = { ...bundle, enabled: false,
        error: { code: 'operation-error' as const, diagnostic: ${JSON.stringify(reason)} } }
      delete unavailable.installTarget
      return unavailable
    })
`)
    .replace(requested, requested + `\n      if (androidUnsupportedBundle(known)) throw new Error(${JSON.stringify(reason)})`) + helper;
});
await patch('packages/subprocess/subprocess/src/index.ts', source => {
  const anchor = '  // A child Node ignores the inherited proxy variables';
  if (!source.includes(anchor)) throw new Error('Child environment contract changed');
  return source.replace(anchor, `  if (process.platform === 'android') {
    for (const name of ['DSH_ANDROID_NATIVE_DIR', 'DSH_ANDROID_RUNTIME', 'DSH_ANDROID_COMMANDS']) {
      const value = process.env[name]
      if (value !== undefined) env[name] = value
    }
  }
` + anchor);
});
await patch('packages/subprocess/subprocess-local/src/index.ts', source => {
  const marker = 'validateSubprocessSpec(spec)';
  if (!source.includes(marker)) throw new Error('Subprocess spawn contract changed');
  let next = source.replaceAll(marker, `if (process.platform === 'android' && process.env.DSH_ANDROID_COMMANDS !== '1') {
      throw new Error('Command execution requires explicit permission in the Android app')
    }
    ${marker}`);
  const terminal = 'async spawnTerminal(';
  // The terminal method may have different return syntax; locate its body from the pinned signature.
  const index = next.indexOf(terminal);
  if (index === -1) throw new Error('Terminal spawn contract changed');
  const body = next.indexOf('{', next.indexOf('):', index));
  next = next.slice(0, body + 1) + `
    if (process.platform === 'android' && process.env.DSH_ANDROID_COMMANDS !== '1') {
      throw new Error('Terminal requires explicit permission in the Android app')
    }
` + next.slice(body + 1);
  return next;
});
const htmlPath = join(root, 'apps/web/index.html');
let html = await readFile(htmlPath, 'utf8');
if (!html.includes('data-android="true"')) html = html.replace('<html', '<html data-android="true"');
if (!html.includes('android-mobile.css')) html = html.replace('</head>', '<link rel="stylesheet" href="/android-mobile.css">\n</head>');
if (!html.includes('android-compat.js')) html = html.replace('<head>', '<head>\n<script src="/android-compat.js"></script>');
await writeFile(htmlPath, html);
await mkdir(join(root, 'apps/web/public'), { recursive: true });
await copyFile(join(port, 'runtime/android/mobile.css'), join(root, 'apps/web/public/android-mobile.css'));
await copyFile(join(port, 'runtime/android/web-compat.js'), join(root, 'apps/web/public/android-compat.js'));
console.log('Applied Android credential, subprocess and mobile patches');
