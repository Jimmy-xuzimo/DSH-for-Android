import http from 'node:http';
import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { syncBuiltinESMExports } from 'node:module';
import childProcess from 'node:child_process';
import { authorizeRequest } from './request-policy.mjs';
import './native-loader.mjs';

const token = process.env.DSH_ANDROID_TOKEN;
if (!token || !/^[a-f0-9]{64}$/.test(token)) {
  throw new Error('Android carrier did not supply a valid launch context');
}
const commandsEnabled = process.env.DSH_ANDROID_COMMANDS === '1';
const originalEmit = http.Server.prototype.emit;
http.Server.prototype.emit = function (event, ...args) {
  if (event === 'request' || event === 'upgrade') {
    const [request, response] = args;
    const address = this.address();
    const origin = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
    if (!authorizeRequest(request, origin, token, event === 'upgrade')) {
      if (event === 'upgrade') response.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
      else { response.writeHead(403, { 'content-type': 'text/plain', 'cache-control': 'no-store' }); response.end('Forbidden'); }
      return true;
    }
  }
  if (event === 'listening') {
    const address = this.address();
    if (typeof address !== 'object' || !address || address.address !== '127.0.0.1') {
      this.close();
      throw new Error('Android host must listen on IPv4 loopback only');
    }
    process.stdout.write(`DSH_ANDROID_READY ${address.port}\n`);
  }
  return originalEmit.call(this, event, ...args);
};

for (const operation of ['spawn', 'spawnSync', 'exec', 'execSync', 'execFile', 'execFileSync', 'fork']) {
  const original = childProcess[operation];
  childProcess[operation] = function (...args) {
    if (!commandsEnabled) {
      const error = new Error('Command execution is disabled. Enable it explicitly in the Android app menu.');
      error.code = 'DSH_ANDROID_COMMANDS_DISABLED';
      throw error;
    }
    const index = ['exec', 'execSync'].includes(operation) ? 1 : (Array.isArray(args[1]) ? 2 : 1);
    const previous = args[index];
    const options = previous && typeof previous === 'object' ? { ...previous } : {};
    options.env = { ...(options.env ?? process.env) };
    for (const name of ['DSH_ANDROID_TOKEN', 'DSH_ANDROID_DATA_KEY']) delete options.env[name];
    if (operation === 'fork') {
      const inherited = options.execArgv ?? process.execArgv;
      const replacement = [];
      const current = fileURLToPath(import.meta.url);
      const nativeOnly = fileURLToPath(new URL('./native-loader.mjs', import.meta.url));
      for (let position = 0; position < inherited.length; position++) {
        const argument = inherited[position];
        const candidate = argument === '--import' ? inherited[position + 1] : argument.startsWith('--import=') ? argument.slice(9) : undefined;
        let carrier = false;
        if (candidate) { try { carrier = realpathSync(candidate) === current; } catch { /* A package import is resolved by Node. */ } }
        if (carrier) { replacement.push('--import', nativeOnly); if (argument === '--import') position++; }
        else replacement.push(argument);
      }
      options.execArgv = replacement;
    }
    if (typeof previous === 'function') args.splice(index, 0, options);
    else args[index] = options;
    return original.apply(this, args);
  };
}
syncBuiltinESMExports();
// Child tools never inherit the transport token or the credential encryption key.
// DSH's subprocess provider independently scrubs its exported environment.

