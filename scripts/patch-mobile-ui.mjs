import { readFile, writeFile, copyFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(process.argv[2] ?? 'runtime/upstream');
const project = fileURLToPath(new URL('..', import.meta.url));
// Every replacement is an upstream contract, including chained replacements.
// Failing one must stop an upgrade rather than silently apply a partial patch.
function contract(source, path) {
  return {
    replace(anchor, replacement) {
      if (!source.includes(anchor)) throw Error('Material bridge anchor changed: ' + path + ': ' + anchor);
      return contract(source.replace(anchor, replacement), path);
    },
    toString() { return source; },
  };
}
async function change(path, transform) {
  const file = join(root, path), source = await readFile(file, 'utf8');
  if (source.includes('DSH_ANDROID_MATERIAL3')) return;
  if (source.includes('DSH_ANDROID_UI_V2')) throw Error('Use a clean candidate before applying Material 3 overlays: ' + path);
  const updated = String(transform(contract(source, path)));
  if (updated === source) throw Error('Material bridge contract changed: ' + path);
  await writeFile(file, '// DSH_ANDROID_MATERIAL3\n' + updated);
}
await copyFile(join(project, 'patches/ui/AndroidNavigation.tsx'), join(root, 'packages/client/ui-sidebar/src/client/AndroidNavigation.tsx'));
await change('packages/client/ui-sidebar/src/client/SidebarRoot.tsx', source =>
  "import { AndroidNavigation } from './AndroidNavigation.tsx'\n" + source.replace('export function SidebarRoot({', 'function DesktopSidebarRoot({') + `
export function SidebarRoot(props: SidebarRootComponentProps) {
  return document.documentElement.dataset.android === 'true' ? <AndroidNavigation {...props} /> : <DesktopSidebarRoot {...props} />
}\n`);
await change('packages/client/ui-layout/src/client/AppFrame.tsx', source => source
  .replace('const sidebarPreference = sidebarCollapsed', "const sidebarPreference = document.documentElement.dataset.android === 'true' ? 0 : sidebarCollapsed")
  .replace('const collapsedWidth = darwin', "const collapsedWidth = document.documentElement.dataset.android === 'true' ? 0 : darwin")
  .replace('const layoutInfo = useStore(state => state.layoutInfo)', `useEffect(() => {
    const conversation = () => actions.selectPanel(null)
    window.addEventListener('dsh-android-conversation', conversation)
    return () => window.removeEventListener('dsh-android-conversation', conversation)
  }, [actions])
  const layoutInfo = useStore(state => state.layoutInfo)`)
  .replace('data-android-compact={viewport < 600 || undefined}', 'data-android-frame')
  .replace('viewport < 600 && !sidebarCollapsed', 'false && !sidebarCollapsed')
  .replace('{!sidebarCollapsed && <DragHandle', "{document.documentElement.dataset.android !== 'true' && !sidebarCollapsed && <DragHandle"));
await change('packages/client/ui-settings-general/src/client/SettingsRoot.tsx', source => source.replace('  let connectionIndicator:', `  useEffect(() => {
    const openNative = () => actions.open()
    const closeNative = () => actions.close()
    window.addEventListener('dsh-material-settings', openNative)
    window.addEventListener('dsh-material-settings-close', closeNative)
    return () => { window.removeEventListener('dsh-material-settings', openNative); window.removeEventListener('dsh-material-settings-close', closeNative) }
  }, [actions])
  useEffect(() => {
    if (open && document.documentElement.dataset.android === 'true') {
      ;(window as unknown as { AndroidHost?: { postMessage: (value: string) => void } }).AndroidHost?.postMessage(JSON.stringify({ action: 'openPreferences' }))
    }
  }, [open])
  let connectionIndicator:`)
  .replace('<div className={css.overlay} role="presentation">', '<div className={css.overlay} role="presentation" data-material-settings-controller={document.documentElement.dataset.android === \'true\' || undefined}>')
  .replace('key={row.id}', 'key={row.id} data-material-section-id={row.id}')
  .replace('const onboardingStep = requestedOnboarding !== undefined', "const onboardingStep = document.documentElement.dataset.android === 'true' ? undefined : requestedOnboarding !== undefined")
  .replace("{onboardingStep !== undefined && renderSlot('settings.onboarding'", "{document.documentElement.dataset.android !== 'true' && onboardingStep !== undefined && renderSlot('settings.onboarding'"));
await change('packages/client/ui-sidebar-right/src/client/shell/ExpandButton.tsx', source => "import { useEffect } from 'react'\n" + source.replace('  if (expanded) return null', `  useEffect(() => {
    const openNative = () => actions.setExpanded(sessionId, true)
    window.addEventListener('dsh-android-tools', openNative)
    return () => window.removeEventListener('dsh-android-tools', openNative)
  }, [actions, sessionId])
  if (expanded) return null`));
await change('packages/client/ui-workspace/src/client/index.ts', source => source.replace('  const openSession: WorkspaceBrowserInjected', `  ctx.effect(() => {
    const selectNative = (event: Event) => {
      const request = (event as CustomEvent<{ action?: string; id?: string }>).detail
      if (request?.action === 'session' && typeof request.id === 'string') uiWorkspace.openSession(request.id as SessionId)
    }
    window.addEventListener('dsh-material-command', selectNative)
    return () => window.removeEventListener('dsh-material-command', selectNative)
  })
  const openSession: WorkspaceBrowserInjected`));
await change('packages/client/ui-theme/src/client/index.ts', source => source.replace("  ctx.provide('theme', theme)", `  ctx.provide('theme', theme)
  if (document.documentElement.dataset.android === 'true') {
    const publishNative = () => {
      const value = theme.getTheme()
      ;(window as unknown as { AndroidHost?: { postMessage: (value: string) => void } }).AndroidHost?.postMessage(JSON.stringify({ action: 'preferences', theme: value.preference, locale: document.documentElement.lang, fontFamilies: value.fontFamilies, ...value.fontSizes }))
    }
    ctx.on('theme/change', publishNative)
    ctx.effect(() => {
      const applyNative = (event: Event) => {
        const request = (event as CustomEvent<{ action?: string; value?: { theme?: string; role?: string; fontSize?: number; fontFamily?: string } }>).detail
        if (request?.action !== 'preference') return
        const value = request.value
        if (value?.theme && ['system', 'light', 'dark'].includes(value.theme)) theme.setTheme(value.theme)
        const role = value?.role
        if ((role === 'text' || role === 'code' || role === 'terminal') && typeof value?.fontFamily === 'string') theme.setFontFamily(role, value.fontFamily)
        if ((role === 'text' || role === 'code' || role === 'terminal') && Number.isInteger(value?.fontSize)) {
          const size = value?.fontSize as number, spec = FONT_SIZE_SPECS[role]
          if (size >= spec.min && size <= spec.max) theme.setFontSize(role, size)
        }
      }
      window.addEventListener('dsh-material-command', applyNative)
      const localeObserver = new MutationObserver(publishNative)
      localeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] })
      publishNative()
      return () => { localeObserver.disconnect(); window.removeEventListener('dsh-material-command', applyNative) }
    })
  }`));
console.log('Applied Compose Material 3 host adapters');

await change('packages/client/ui-theme/src/client/FontSizeRow.tsx', source => source.replace('  return (\n    <div className={css.row}>', `  if (document.documentElement.dataset.android === 'true') return null
  return (\n    <div className={css.row}>`));
await change('packages/client/ui-theme/src/client/AppearanceRow.tsx', source => source.replace('  return (', "  if (document.documentElement.dataset.android === 'true') return null\n  return ("));
await change('packages/client/ui-theme/src/client/FontSettingsGroup.tsx', source => source.replace('  return (', "  if (document.documentElement.dataset.android === 'true') return null\n  return ("));
