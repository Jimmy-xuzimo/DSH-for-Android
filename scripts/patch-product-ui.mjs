// Product-level overlays on the pinned DSH interfaces. Fail closed on upgrades.
import { readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
const root=resolve(process.argv[2] ?? 'runtime/upstream');
async function edit(path, replacements) {
  const filename=join(root,path); let source=await readFile(filename,'utf8');
  if(source.includes('DSH_ANDROID_PRODUCT')) return;
  for(const [anchor,value] of replacements) {
    if(!source.includes(anchor)) throw Error('Product overlay contract changed: '+path+' '+anchor.slice(0,100));
    source=source.replace(anchor,value);
  }
  await writeFile(filename,'// DSH_ANDROID_PRODUCT\n'+source);
}
const ui='packages/client/ui-settings-models/src/client/';
await edit(ui+'ProviderEditor.tsx',[
  ['  DeepSeekModelsEditor, modelDrafts, validateDeepSeekModels,','  modelDrafts, validateDeepSeekModels,'],
  ["const probeBaseURL = stringAt(draft, 'baseURL') ?? stringAt(fallback, 'baseURL')", "const probeBaseURL = stringAt(draft, 'baseURL') ?? stringAt(fallback, 'baseURL') ?? props.defaultBaseURL ?? (layout === 'deepseek' ? 'https://api.deepseek.com/anthropic' : undefined)"],
  ["const models = modelDrafts(modelsOverridden ? customModels : inheritedModels())", "const models = modelDrafts(modelsOverridden ? customModels : [])"],
  ["value={stringAt(draft, 'baseURL') ?? ''}","value={probeBaseURL ?? ''}"],
  ["<details className={styles['customized']}>","<details open className={styles['customized']}>"],
  ["{family === 'deepseek'\n              ? (\n                <DeepSeekModelsEditor\n                  {...catalogProps}\n                  defaultContextWindow={typeof defaultContextWindow === 'number'\n                    ? defaultContextWindow\n                    : undefined}\n                  defaultMaxTokens={typeof defaultMaxTokens === 'number' ? defaultMaxTokens : undefined}\n                />\n              )\n              : (", "{("],
  ["  const inheritedModels = (): unknown => {", "  const inheritedModels = (): unknown => {"],
]);
// Remove declarations that belonged exclusively to the inherited fixed catalog.
{
  const path=join(root,ui+'ProviderEditor.tsx');let source=await readFile(path,'utf8');
  source=source.replace(/  const inheritedModels = \(\): unknown => \{[\s\S]*?\n  \}\n/, '');
  source=source.replace("    const defaultContextWindow = schema.getPath(fallback, ['defaultContextWindow'])\n",'').replace("    const defaultMaxTokens = schema.getPath(fallback, ['maxTokens'])\n",'');
  await writeFile(path,source);
}
await edit(ui+'ModelsSection.tsx',[
  [', Modal, SegmentedControl }', ', Modal }'],
  ["useState<AddMode>('catalog')","useState<AddMode>('custom')"],
  ["const initial: AddMode = catalogEnabled ? 'catalog' : 'custom'","const initial: AddMode = customEnabled ? 'custom' : 'catalog'"],
  ["options={[\n                        {\n                          value: 'catalog',", "options={[\n                        {\n                          value: 'custom',\n                          label: t('addCustom'),\n                          disabled: !customEnabled,\n                          ...customEnabled ? {} : { title: t('addCustomUnavailable') },\n                        },\n                        {\n                          value: 'catalog',"],
  ["                        {\n                          value: 'custom',\n                          label: t('addCustom'),\n                          disabled: !customEnabled,\n                          ...customEnabled ? {} : { title: t('addCustomUnavailable') },\n                        },\n                      ]}", "                      ]}"],
]);
{
  const path=join(root,ui+'ModelsSection.tsx');let source=await readFile(path,'utf8');
  const switcher=/<SegmentedControl[\s\S]*?\n                    \/>/;
  if(!switcher.test(source)) throw Error('Provider selector contract changed');
  source=source.replace(switcher,`<select id={addId} aria-label={t('provider')} disabled={switchLocked} value={mode === 'custom' ? '__custom__' : draft?.target.provider ?? ''} onChange={event => {
                      if(event.target.value === '__custom__') { setAddMode('custom'); setVisited(previous => new Set([...previous, 'custom'])) }
                      else { const picked=addable.find(candidate => candidate.row.entry.provider === event.target.value); if(picked === undefined) return; setEditing(targetOf(picked.row)); setAddMode('catalog'); setVisited(previous => new Set([...previous,'catalog'])) }
                    }}>
                      <option value="__custom__" disabled={!customEnabled}>{t('addCustom')}</option>
                      {addable.map(({row}) => <option key={row.entry.provider} value={row.entry.provider}>{row.entry.displayName}</option>)}
                    </select>`);
  const duplicate=/<div className=\{styles\['field'\]\}>\n                      <span className=\{styles\['fieldLabel'\]\}>\{t\('provider'\)\}<\/span>[\s\S]*?<\/div>/;
  if(!duplicate.test(source))throw Error('Duplicate catalog selector contract changed');
  source=source.replace(duplicate,'');
  await writeFile(path,source);
}
await edit('packages/llm/llm-pi-ai/src/discovery.ts',[
  ['if (request.provider !== undefined) {','if (request.provider !== undefined && request.baseURL === undefined) {'],
  ["      method: 'GET',\n      headers,", "      method: 'GET',\n      redirect: 'error',\n      headers,"],
]);
await edit('packages/llm/llm-deepseek-api-key/src/index.ts',[
  ['  const listings = new ModelListingCache({', `  ctx.llm.registerModelDiscovery(ctx.fiber.entry?.options.id ?? name, async (request, signal) => {
    const connection = options()
    const apiKey = request.apiKey === undefined ? await resolveApiKey(connection) : assertUsableApiKey(request.apiKey, 'llm-deepseek', connection.apiKeyEnv)
    const base = new URL(request.baseURL ?? connection.baseURL)
    if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password) throw new LlmError('Invalid model listing endpoint', 'DISCOVERY_FAILED')
    const deadline = AbortSignal.timeout(15000)
    const listing = await requestModelListing(new URL('/models', base.origin).href, { authorization: 'Bearer ' + apiKey }, signal === undefined ? deadline : AbortSignal.any([signal, deadline]))
    return listing.entries.map(entry => ({ id: entry.id, ...entry.name === undefined ? {} : { name: entry.name } }))
  })
  const listings = new ModelListingCache({`],
]);
console.log('Applied live model discovery and unified provider setup overlays');
