import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { writeFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { once } from 'node:events';
const require = createRequire(import.meta.resolve('@deepseek-ai/dsh-mcp-client'));
const { Client, StreamableHTTPClientTransport } = require('@modelcontextprotocol/client');
const { StdioClientTransport } = require('@modelcontextprotocol/client/stdio');
const { scrubbedParentEnv } = require('@deepseek-ai/dsh-subprocess');

function answer(request) {
  if (request.id === undefined) return undefined;
  const result = request.method === 'initialize' ? {
    protocolVersion: request.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'android-fixture', version: '1.0' },
  } : request.method === 'tools/list' ? {
    tools: [{ name: 'echo', description: 'Android integration fixture', inputSchema: { type: 'object', properties: { text: { type: 'string' } } } }],
  } : request.method === 'tools/call' ? { content: [{ type: 'text', text: request.params.arguments.text }] } : {};
  return { jsonrpc: '2.0', id: request.id, result };
}
if (process.argv.includes('--stdio')) {
  if (process.env.DSH_ANDROID_TOKEN || process.env.DSH_ANDROID_DATA_KEY) throw Error('MCP inherited a carrier secret');
  let pending = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', chunk => {
    pending += chunk;
    while (pending.includes('\n')) { const end = pending.indexOf('\n'); const line = pending.slice(0, end); pending = pending.slice(end + 1);
      if (line.trim()) { const response = answer(JSON.parse(line)); if (response) process.stdout.write(JSON.stringify(response) + '\n'); }
    }
  });
} else {
  const { Context } = require('@deepseek-ai/cordis');
  const packagePath = process.cwd() + '/fixture-package'; mkdirSync(packagePath);
  writeFileSync(packagePath + '/package.json', JSON.stringify({ name: 'dsh-android-fixture-plugin', version: '1.0.0', type: 'module', main: 'index.mjs' }));
  const pluginPath = packagePath + '/index.mjs';
  writeFileSync(pluginPath, 'export function apply(ctx){globalThis.__DSH_EXTENSION_TEST="JS_PLUGIN_OK";ctx.effect(()=>()=>{globalThis.__DSH_EXTENSION_TEST="DISPOSED"})}');
  const install = spawnSync('pnpm', ['add', '--offline', '--ignore-scripts', './fixture-package'], { env: scrubbedParentEnv(), encoding: 'utf8', timeout: 20000 });
  if (install.status !== 0) throw Error('Compatible JS plugin installation failed: ' + install.stdout + install.stderr);
  const plugin = await import('file://' + process.cwd() + '/node_modules/dsh-android-fixture-plugin/index.mjs');
  const context = new Context(); const scope = context.plugin(plugin); await new Promise(resolve => setTimeout(resolve, 50));
  if (globalThis.__DSH_EXTENSION_TEST !== 'JS_PLUGIN_OK') throw Error('Compatible JS plugin did not activate');
  await scope.dispose(); if(globalThis.__DSH_EXTENSION_TEST !== 'DISPOSED')throw Error('Plugin cleanup failed');console.log('JS_PLUGIN_OK');

  const local = new Client({ name: 'android-test', version: '1.0' });
  const stdio = new StdioClientTransport({ command: process.execPath, args: [new URL(import.meta.url).pathname, '--stdio'], env: scrubbedParentEnv() });
  await local.connect(stdio);
  if ((await local.listTools()).tools[0]?.name !== 'echo') throw Error('stdio tool discovery failed');
  if ((await local.callTool({ name: 'echo', arguments: { text: 'STDIO_MCP_OK' } })).content[0]?.text !== 'STDIO_MCP_OK') throw Error('stdio call failed');
  await local.close(); console.log('STDIO_MCP_OK');

  const server = createServer(async (request, response) => {
    if (request.method !== 'POST') { response.writeHead(405); response.end(); return; }
    let body = ''; for await (const chunk of request) body += chunk;
    const result = answer(JSON.parse(body));
    if (!result) { response.writeHead(202); response.end(); return; }
    response.setHeader('content-type', 'application/json'); response.end(JSON.stringify(result));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const remote = new Client({ name: 'android-network-test', version: '1.0' });
  try {
    await remote.connect(new StreamableHTTPClientTransport(new URL('http://127.0.0.1:' + server.address().port)));
    if ((await remote.listTools()).tools[0]?.name !== 'echo') throw Error('network tool discovery failed');
    if ((await remote.callTool({ name: 'echo', arguments: { text: 'HTTP_MCP_OK' } })).content[0]?.text !== 'HTTP_MCP_OK') throw Error('network call failed');
    console.log('HTTP_MCP_OK');
  } finally { await remote.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}

