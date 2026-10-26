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
