/* Loaded before upstream modules. Android's OS and WebView update independently. */
if (!Promise.withResolvers) Object.defineProperty(Promise, 'withResolvers', {
  configurable: true, writable: true, value: function withResolvers() {
    let resolve, reject;
    const promise = new this((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
  },
});
if (!Array.prototype.toSorted) Object.defineProperty(Array.prototype, 'toSorted', {
  configurable: true, writable: true, value: function toSorted(compare) { return Array.from(this).sort(compare); },
});
if (!Array.prototype.toReversed) Object.defineProperty(Array.prototype, 'toReversed', {
  configurable: true, writable: true, value: function toReversed() { return Array.from(this).reverse(); },
});

// Appearance is the only automatic carrier message; never send paths or data.
(() => {
  const animate = typeof Element !== 'undefined' && Element.prototype.animate;
  if (animate) Element.prototype.animate = function(frames, options) {
    if (document.documentElement.dataset.androidReduceMotion === 'true') options = typeof options === 'number' ? 0 : { ...options, duration: 0, delay: 0, endDelay: 0, iterations: 1 };
    return animate.call(this, frames, options);
  };
  const updateAppearance = () => {
    const dark = getComputedStyle(document.documentElement).colorScheme === 'dark';
    window.AndroidHost?.postMessage(JSON.stringify({ action: 'appearance', dark }));
  };
  new MutationObserver(updateAppearance).observe(document.documentElement, { attributes: true, attributeFilter: ['style'] });
  document.addEventListener('DOMContentLoaded', updateAppearance, { once: true });
})();
