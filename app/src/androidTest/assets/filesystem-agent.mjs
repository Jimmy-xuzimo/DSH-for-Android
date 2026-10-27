import assert from 'node:assert/strict';
import { readFileSync, statSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const runtime = process.env.DSH_ANDROID_RUNTIME;
const inventory = JSON.parse(readFileSync(runtime + '/package-inventory.json', 'utf8'));
const local = inventory.find(item => item.name === '@deepseek-ai/dsh-fs-local');
const entry = runtime + '/' + local.path + '/lib/index.js';
const { LocalFileSystem } = await import(pathToFileURL(entry));
const { Context } = createRequire(entry)('@deepseek-ai/cordis');
const context = new Context();
const fs = new LocalFileSystem(context, { cwd: process.cwd(), diffBasisMaxBytes: 1024 * 1024 });
const target = await fs.resolve('agent-created.txt');
await fs.writeText(target, 'agent-created\n中文\n', { kind: 'createIfAbsent' });
assert.equal(statSync('agent-created.txt').mode & 0o777, 0o600);
assert.equal(await fs.readText(target), 'agent-created\n中文\n');
await assert.rejects(fs.writeText(target, 'must-not-overwrite', { kind: 'createIfAbsent' }), { code: 'FS_NOT_OBSERVED' });
const observed = await fs.stat(target);
await fs.editText(target, { oldString: 'agent-created', newString: 'agent-edited', replaceAll: false }, { version: observed.version });
assert.match(await fs.readText(target), /agent-edited/);
await assert.rejects(fs.writeText(target, 'stale-write', { kind: 'replaceIfVersion', version: observed.version }), { code: 'FS_STALE_VERSION' });
const other = new LocalFileSystem(new Context(), { cwd: process.cwd(), diffBasisMaxBytes: 1024 * 1024 });
const race = await fs.resolve('agent-race.txt');
const results = await Promise.allSettled([
  fs.writeText(race, 'winner-a', { kind: 'createIfAbsent' }),
  other.writeText(race, 'winner-b', { kind: 'createIfAbsent' }),
]);
assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
assert.equal(results.find(result => result.status === 'rejected').reason.code, 'FS_NOT_OBSERVED');
assert.match(readFileSync('agent-race.txt', 'utf8'), /^winner-[ab]$/);
const cancelled = new AbortController(); cancelled.abort();
await assert.rejects(fs.writeText(await fs.resolve('cancelled.txt'), 'never', { kind: 'createIfAbsent' }, cancelled.signal));
assert.equal(existsSync('cancelled.txt'), false);
console.log('AGENT_FILESYSTEM_OK create/read/edit/no-clobber/stale/concurrent-create/abort/private-mode');
