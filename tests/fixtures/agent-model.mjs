// Deterministic model fixture. DSH executes the real tools; this server only
// emits model protocol messages. Bind locally and use adb reverse for devices.
import http from 'node:http';
import { writeFileSync, appendFileSync } from 'node:fs';
const port = Number(process.env.DSH_TEST_MODEL_PORT || 4300);
const log = process.env.DSH_TEST_MODEL_LOG;
const filename = `.agent-call-check-${Date.now()}.txt`;
const steps = [
  ['write', { file_path: filename, content: 'ANDROID_AGENT_CREATED\n中文工具调用\n' }],
  ['read', { file_path: filename }],
  ['edit', { file_path: filename, old_string: 'ANDROID_AGENT_CREATED', new_string: 'ANDROID_AGENT_EDITED' }],
  ['grep', { pattern: 'ANDROID_AGENT_EDITED', path: '.', include: filename }],
  ['bash', { command: `cat ${filename}; git --version; rg --version; printf '\\nANDROID_AGENT_SHELL_OK\\n'`, description: 'Verify workspace file, Git and search on Android' }],
];
const expected = [['Created file'], ['ANDROID_AGENT_CREATED', '中文工具调用'], ['updated successfully'], ['Found 1 match', 'ANDROID_AGENT_EDITED'], ['ANDROID_AGENT_EDITED', '中文工具调用', 'git version', 'ripgrep', 'ANDROID_AGENT_SHELL_OK']];
http.createServer((request, response) => {
  let body = '';
  request.on('data', chunk => { body += chunk; if (body.length > 8 * 1024 * 1024) request.destroy(); });
  request.on('end', () => {
    response.setHeader('content-type', 'application/json');
    if (request.url.endsWith('/models')) return response.end(JSON.stringify({ data: [{ id: 'deepseek-chat', object: 'model' }] }));
    if (!request.url.endsWith('/messages')) { response.statusCode = 404; return response.end('{}'); }
    const input = JSON.parse(body);
    const blocks = input.messages.flatMap(message => Array.isArray(message.content) ? message.content : []);
    const results = blocks.filter(block => block.type === 'tool_result');
    const issued = blocks.filter(block => block.type === 'tool_use' && block.id?.startsWith('android-qa-'));
    const step = issued.length;
    if (log) {
      if (step === 0) writeFileSync(log + '.tools.json', JSON.stringify(input.tools ?? [], null, 2));
      appendFileSync(log, JSON.stringify({ step, results: results.slice(-1), tools: input.tools?.map(tool => tool.name) }) + '\n');
    }
    const last = results.at(-1);
    const failed = issued.some((call, index) => {
      const result = results.find(value => value.tool_use_id === call.id);
      const text = JSON.stringify(result?.content);
      return !result || result.is_error || /<error>|permission denied|requires explicit permission/i.test(text)
        || !expected[index]?.every(marker => text.includes(marker));
    });
    let content;
    if (!input.tools?.length) content = [{ type: 'text', text: 'Android tools check' }];
    else if (failed) content = [{ type: 'text', text: 'ANDROID_AGENT_TOOL_FAILED: ' + JSON.stringify(last.content) }];
    else if (step < steps.length) {
      const [name, args] = steps[step];
      const tool = input.tools?.find(tool => tool.name === name);
      content = tool ? [{ type: 'tool_use', id: `android-qa-${step}`, name, input: args }]
        : [{ type: 'text', text: `ANDROID_AGENT_MISSING_TOOL: ${name}` }];
    } else content = [{ type: 'text', text: 'ANDROID_AGENT_TOOLS_OK — write, read, edit, grep and bash completed. 中文文件已读写，工具结果已回传。' }];
    const message = { id: 'android-qa-response', type: 'message', role: 'assistant', model: input.model,
      content, stop_reason: content[0].type === 'tool_use' ? 'tool_use' : 'end_turn', stop_sequence: null,
      usage: { input_tokens: 20, output_tokens: 20 } };
    if (!input.stream) return response.end(JSON.stringify(message));
    response.setHeader('content-type', 'text/event-stream');
    const emit = (event, data) => response.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    emit('message_start', { type: 'message_start', message: { ...message, content: [], stop_reason: null } });
    content.forEach((block, index) => {
      emit('content_block_start', { type: 'content_block_start', index, content_block: block.type === 'tool_use' ? { ...block, input: {} } : { type: 'text', text: '' } });
      emit('content_block_delta', { type: 'content_block_delta', index, delta: block.type === 'tool_use'
        ? { type: 'input_json_delta', partial_json: JSON.stringify(block.input) } : { type: 'text_delta', text: block.text } });
      emit('content_block_stop', { type: 'content_block_stop', index });
    });
    emit('message_delta', { type: 'message_delta', delta: { stop_reason: message.stop_reason, stop_sequence: null }, usage: { output_tokens: 20 } });
    emit('message_stop', { type: 'message_stop' }); response.end();
  });
}).listen(port, '127.0.0.1', () => console.log(`Agent model fixture listening on ${port}`));
