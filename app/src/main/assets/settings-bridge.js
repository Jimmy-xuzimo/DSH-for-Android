/* Semantic controller adapter. Visible settings are Compose Material 3.
 * IDs refer only to live controls in the DSH settings scope, never selectors,
 * paths or scripts supplied by the native UI. Password values never leave DOM.
 */
(function installMaterialSettings() {
  if (window.self !== window.top) return;
  if (!document.body) { document.addEventListener('DOMContentLoaded', installMaterialSettings, { once: true }); return; }
  if (window.__dshMaterialSettings) return;
  window.__dshMaterialSettings = true;
  const ids = new WeakMap();
  const secrets = new WeakSet();
  let sequence = 0, active = new Map(), previous = '', timer;
  const clean = value => String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, 8192);
  const visible = element => {
    if (!(element instanceof HTMLElement) || element.closest('[hidden],[aria-hidden="true"],script,style,svg')) return false;
    const style = getComputedStyle(element);
    return style.display !== 'none' && style.visibility !== 'hidden' && (style.display === 'contents' || element.getClientRects().length > 0);
  };
  const identify = element => {
    if (!ids.has(element)) ids.set(element, `setting-${++sequence}`);
    const id = ids.get(element); active.set(id, element); return id;
  };
  const label = element => clean(element.getAttribute('aria-label') ||
    (element.getAttribute('aria-labelledby') || '').split(' ').map(id => document.getElementById(id)?.textContent || '').join(' ') ||
    [...(element.labels || [])].map(item => item.textContent).join(' ') || element.textContent || element.title || element.getAttribute('placeholder'));
  const disabled = element => element.matches(':disabled') || element.getAttribute('aria-disabled') === 'true';
  function nodes(root) {
    const output = [];
    function walk(element) {
      if (!visible(element)) return;
      const role = element.getAttribute('role'), tag = element.tagName.toLowerCase();
      if (['svg', 'img', 'script', 'style', 'canvas'].includes(tag)) return;
      if (element.matches('[data-material-appearance-owned]')) return;
      if (element.matches('input[type=hidden]')) return;
      if (element.matches('input,textarea,select') || element.isContentEditable && role === 'textbox') {
        if (element.type === 'password' || /password|api.?key|secret|密钥/i.test(element.name || '') || element.autocomplete === 'current-password' || element.autocomplete === 'new-password') secrets.add(element);
        const secret = secrets.has(element);
        const value = secret ? '' : String(element.value ?? element.textContent ?? '').slice(0, 65536);
        const kind = tag === 'select' ? 'select' : element.type === 'checkbox' ? 'switch' : element.type === 'radio' ? 'radio' : element.type === 'range' ? 'slider' : 'input';
        output.push({ id: identify(element), kind, label: label(element), value, secret, multiline: tag === 'textarea' || element.isContentEditable,
          inputType: element.type || 'text', disabled: disabled(element), checked: !!element.checked, min: element.min, max: element.max, step: element.step,
          placeholder: element.getAttribute('placeholder') || '', invalid: element.getAttribute('aria-invalid') === 'true',
          options: tag === 'select' ? [...element.options].map(option => ({ label: option.label, value: option.value, disabled: option.disabled })) : [] });
        return;
      }
      if (tag === 'button' || ['button', 'switch', 'checkbox', 'radio', 'tab', 'option', 'menuitem', 'menuitemradio', 'menuitemcheckbox', 'combobox'].includes(role)) {
        // The native password field owns visibility; a second DOM toggle
        // changes only the hidden controller and cannot reveal native text.
        if (/^(显示密钥|隐藏密钥|Show key|Hide key|Show API key|Hide API key)$/i.test(label(element))) return;
        const reorder = element.draggable && element.getAttribute('aria-keyshortcuts')?.includes('ArrowUp ArrowDown');
        const row = reorder ? element.closest('[data-row-key]') : null;
        const kind = reorder ? 'reorder' : ['switch','checkbox','menuitemcheckbox'].includes(role) ? 'switch' : ['radio','menuitemradio'].includes(role) ? 'radio' : 'button';
        output.push({ id: identify(element), kind, label: label(element) || clean(element.getAttribute('data-tooltip')) || '⋯',
          canUp: !!row?.previousElementSibling?.hasAttribute('data-row-key'), canDown: !!row?.nextElementSibling?.hasAttribute('data-row-key'),
          disabled: disabled(element), checked: element.getAttribute('aria-checked') === 'true' || element.getAttribute('aria-selected') === 'true' || element.getAttribute('aria-pressed') === 'true',
          popup: element.hasAttribute('aria-haspopup') || role === 'combobox', role });
        return;
      }
      if (tag === 'a' && element.href) {
        if (/^https?:/.test(element.href)) output.push({ id: identify(element), kind: 'link', label: label(element), href: element.href });
        return;
      }
      if (tag === 'hr') { output.push({ id: identify(element), kind: 'divider' }); return; }
      const heading = /^h[1-6]$/.test(tag) || role === 'heading';
      if (heading) { output.push({ id: identify(element), kind: 'heading', label: clean(element.textContent) }); return; }
      for (const child of element.childNodes) {
        if (child.nodeType === Node.TEXT_NODE) {
          const text = clean(child.textContent);
          if (text) output.push({ id: `${identify(element)}-text-${output.length}`, kind: 'text', label: text });
        } else if (child instanceof HTMLElement) walk(child);
      }
    }
    walk(root);
    return output.slice(0, 1000);
  }
  function publish() {
    const panel = document.querySelector('[data-shortcut-modal="settings"]');
    if (!panel || !visible(panel)) {
      active.clear();
      if (previous !== 'closed') { previous = 'closed'; window.AndroidHost?.postMessage(JSON.stringify({ action: 'settingsState', ready: false, sections: [], nodes: [], overlays: [] })); }
      return;
    }
    active = new Map();
    const navigation = panel.querySelector('nav');
    const sections = navigation ? [...navigation.querySelectorAll('button')].filter(visible).map(button => ({ id: identify(button), key: button.dataset.materialSectionId || identify(button), label: label(button), selected: button.getAttribute('aria-current') === 'true' })) : [];
    const seat = panel.querySelector('[data-slot="settings.section"]');
    const content = seat?.parentElement || panel.lastElementChild;
    const overlays = [...document.querySelectorAll('[role=dialog],[role=alertdialog],[role=menu],[role=listbox]')]
      .filter(element => element !== panel && !panel.contains(element) && visible(element))
      .filter((element, index, all) => !all.some(other => other !== element && other.contains(element)))
      .map(element => ({ id: identify(element), label: label(element).slice(0, 120), kind: element.getAttribute('role'), nodes: nodes(element) }));
    const snapshot = { ready: true, sections, nodes: content ? nodes(content) : [], overlays };
    const message = JSON.stringify({ action: 'settingsState', ...snapshot });
    if (message !== previous && message.length <= 524288) { previous = message; window.AndroidHost?.postMessage(message); }
  }
  // Throttle, do not debounce: live session/status updates can mutate forever.
  // A debounce would starve settings acknowledgements until the page is idle.
  const schedule = () => {
    if (timer !== undefined) return;
    timer = setTimeout(() => { timer = undefined; publish(); }, 60);
  };
  const observer = new MutationObserver(schedule);
  observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true,
    attributeFilter: ['value','type','checked','selected','disabled','aria-checked','aria-selected','aria-current','aria-expanded','aria-invalid','aria-disabled','hidden','class'] });
  document.addEventListener('input', schedule, true);
  document.addEventListener('change', schedule, true);
  window.addEventListener('dsh-material-command', event => {
    const request = event.detail;
    if (request?.action === 'settingsRefresh') { previous = ''; schedule(); return; }
    if (request?.action !== 'settingsControl') return;
    const element = active.get(request.id);
    if (!element || !element.isConnected || !visible(element) || disabled(element)) return;
    if (request.operation === 'click' && element.matches('button,[role=button],[role=switch],[role=checkbox],[role=radio],[role=tab],[role=option],[role^=menuitem],[role=combobox],input[type=checkbox],input[type=radio]')) element.click();
    if (request.operation === 'key' && ['ArrowUp','ArrowDown'].includes(request.value) && element.getAttribute('aria-keyshortcuts')?.split(' ').includes(request.value)) element.dispatchEvent(new KeyboardEvent('keydown', { key: request.value, bubbles: true }));
    if (request.operation === 'input' && typeof request.value === 'string' && request.value.length <= 65536 && element.matches('input:not([type=file]):not([type=hidden]),textarea,select')) {
      const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, 'value').set.call(element, request.value);
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
    }
    if (request.operation === 'dismiss') document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    schedule();
  });
  publish();
})();
