import { useEffect, useState } from 'react'
import type { SidebarRootComponentProps } from './contract/slots.ts'

/** Typed adapter only. Material 3 navigation and preferences live in Compose. */
export function AndroidNavigation({ startSession, selectPanel, usePanels, useSessions, renderSlot }: SidebarRootComponentProps) {
  const panels = usePanels(value => value)
  const sessions = useSessions(value => value)
  const [manage, setManage] = useState(false)
  useEffect(() => {
    const receive = (event: Event) => {
      const request = (event as CustomEvent<{ action?: string; id?: string }>).detail
      if (request?.action === 'new') startSession()
      if (request?.action === 'panel') {
        const panel = panels.find(value => value.id === request.id)
        if (panel) selectPanel(panel.id)
      }
      if (request?.action === 'settings') window.dispatchEvent(new Event('dsh-material-settings'))
      if (request?.action === 'settingsClose') window.dispatchEvent(new Event('dsh-material-settings-close'))
      if (request?.action === 'tools') window.dispatchEvent(new Event('dsh-android-tools'))
      if (request?.action === 'conversation') window.dispatchEvent(new Event('dsh-android-conversation'))
      if (request?.action === 'manageSessions') setManage(true)
    }
    window.addEventListener('dsh-material-command', receive)
    return () => window.removeEventListener('dsh-material-command', receive)
  }, [startSession, selectPanel, panels])
  useEffect(() => {
    const payload = { action: 'navigationState', panels: panels.map(value => ({ id: value.id, title: value.label })),
      sessions: Object.values(sessions.byId).map(value => ({ id: value.id, title: value.title ?? '' })) }
    ;(window as unknown as { AndroidHost?: { postMessage: (value: string) => void } }).AndroidHost?.postMessage(JSON.stringify(payload))
  }, [panels, sessions])
  return <div className="material-session-manager" hidden={!manage}>
    {manage && <button type="button" onClick={() => setManage(false)}>{document.documentElement.lang.startsWith('zh') ? '关闭会话管理' : 'Close session manager'}</button>}
    <div>{renderSlot('sidebar.workspaces', { wide: true, expandSidebar: () => setManage(true) })}</div>
    <div hidden>{renderSlot('sidebar.settings', { wide: true })}</div>
  </div>
}
