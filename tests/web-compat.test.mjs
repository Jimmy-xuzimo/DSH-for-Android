import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

test('older WebView gets deferred promises and non-mutating array ordering', async () => {
  const source = readFileSync(new URL('../runtime/android/web-compat.js', import.meta.url), 'utf8');
  const result = runInNewContext(`
    Promise.withResolvers=undefined;Array.prototype.toSorted=undefined;Array.prototype.toReversed=undefined;
    ${source}
    (async()=>{class Child extends Promise{};const deferred=Child.withResolvers();deferred.resolve(42);
      const original=[3,1,2];return {value:await deferred.promise,subclass:deferred.promise instanceof Child,
        original:JSON.stringify(original),sorted:JSON.stringify(original.toSorted()),reversed:JSON.stringify(original.toReversed())};})()
  `);
  const value = await result;
  assert.equal(value.value, 42); assert.equal(value.subclass, true);
  assert.equal(value.original, '[3,1,2]'); assert.equal(value.sorted, '[1,2,3]'); assert.equal(value.reversed, '[2,1,3]');
});
