import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authorizeRequest } from '../runtime/android/request-policy.mjs';

const origin = 'http://127.0.0.1:3080';
const token = 'a'.repeat(64);
const request = (headers = {}, method = 'GET') => ({ method, headers: { host: '127.0.0.1:3080', cookie: `dsh_android=${token}`, ...headers } });
test('authenticated same-origin requests are accepted', () => {
  assert.equal(authorizeRequest(request(), origin, token), true);
  assert.equal(authorizeRequest(request({ origin }, 'POST'), origin, token), true);
  assert.equal(authorizeRequest(request({ origin }), origin, token, true), true);
});
test('other apps without the launch cookie cannot reach the engine', () => {
  for (const cookie of ['', 'dsh_android=wrong', `dsh_android=${token}; dsh_android=${token}`]) {
    assert.equal(authorizeRequest(request({ cookie }), origin, token), false);
  }
});
test('cross-origin, DNS-rebound and opaque-origin requests are refused', () => {
  for (const header of ['null', 'https://attacker.example', 'http://localhost:3080']) {
    assert.equal(authorizeRequest(request({ origin: header }), origin, token), false);
  }
  assert.equal(authorizeRequest(request({ host: 'attacker.example:3080' }), origin, token), false);
  assert.equal(authorizeRequest(request({}, 'POST'), origin, token), false);
  assert.equal(authorizeRequest(request(), origin, token, true), false);
});
