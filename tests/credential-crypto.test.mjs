import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';

test('credentials stay encrypted and authenticated across reloads', async () => {
  const key = randomBytes(32).toString('base64');
  process.env.DSH_ANDROID_DATA_KEY = key;
  const writer = await import('../patches/credential-crypto.ts?writer');
  const secret = 'version: 1\nrefs:\n  DEEPSEEK_API_KEY: secret-example\n';
  const encoded = writer.encryptCredentials(secret);
  assert.ok(!encoded.includes('secret-example'));
  assert.equal(process.env.DSH_ANDROID_DATA_KEY, undefined);
  process.env.DSH_ANDROID_DATA_KEY = key;
  const reader = await import('../patches/credential-crypto.ts?reader');
  assert.equal(reader.decryptCredentials(encoded), secret);
  assert.throws(() => reader.decryptCredentials(secret));
  const damaged = encoded.slice(0, -8) + 'AAAAAAAA';
  assert.throws(() => reader.decryptCredentials(damaged));
  process.env.DSH_ANDROID_DATA_KEY = randomBytes(32).toString('base64');
  const other = await import('../patches/credential-crypto.ts?other');
  assert.throws(() => other.decryptCredentials(encoded));
});
