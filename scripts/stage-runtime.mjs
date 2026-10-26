import { readFileSync, writeFileSync, mkdirSync, readdirSync, realpathSync, existsSync, copyFileSync, symlinkSync, lstatSync, globSync } from 'node:fs';
import { resolve, join, dirname, relative, basename } from 'node:path';
import { createHash } from 'node:crypto';

const upstream = resolve(process.argv[2]);
const destination = resolve(process.argv[3]);
if (existsSync(destination)) throw new Error('Staging destination must be new');
mkdirSync(destination, { recursive: true });
const packages = new Map();
const edges = [];
function findDependency(directory, name) {
  let current = directory;
  while (true) {
    const candidate = join(current, 'node_modules', name);
    if (existsSync(candidate)) return realpathSync(candidate);
    const parent = dirname(current);
    if (parent === current) throw new Error(`Missing installed runtime dependency ${name} from ${directory}`);
    current = parent;
  }
}
function include(directory) {
  directory = realpathSync(directory);
  if (packages.has(directory)) return packages.get(directory);
  const manifest = JSON.parse(readFileSync(join(directory, 'package.json'), 'utf8'));
  const id = createHash('sha256').update(`${manifest.name}\0${manifest.version}\0${relative(upstream, directory)}`).digest('hex').slice(0, 20);
  const target = join(destination, 'store', id);
  packages.set(directory, target);
  mkdirSync(target, { recursive: true });
  let allow;
  if (manifest.files) {
    // npm always publishes the main entry and executable entry points, even
    // when they are absent from `files` (cross-spawn relies on this rule).
    const bins = typeof manifest.bin === 'string' ? [manifest.bin] : Object.values(manifest.bin ?? {});
    const entries = [manifest.main, manifest.module, ...bins].filter(value => typeof value === 'string');
    const patterns = [...manifest.files.filter(p => !p.startsWith('!')), ...entries];
    allow = new Set(['package.json', ...globSync(patterns.flatMap(p => [p, `${p}/**/*`]), { cwd: directory })]);
  }
  function copyTree(from, to, prefix = '') {
    for (const entry of readdirSync(from, { withFileTypes: true })) {
      const name = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.name === 'node_modules' || entry.name === '.git' || entry.name === '.cache') continue;
      if (allow && entry.name.startsWith('.') && allow.has(prefix)) allow.add(name);
      if (allow && !allow.has(name) && ![...allow].some(path => path.startsWith(name + '/')) && !/^(licen[cs]e|copying|notice|readme)/i.test(entry.name)) continue;
      if (entry.isSymbolicLink()) throw new Error(`Unexpected package source symlink: ${directory}/${name}`);
      if (entry.isDirectory()) {
        if (['test', 'tests', '__tests__', 'prebuilds', 'vendor'].includes(entry.name) && !allow) continue;
        copyTree(join(from, entry.name), join(to, entry.name), name);
      } else if (entry.isFile()) {
        const notice = /^(licen[cs]e|copying|notice|readme)/i.test(entry.name);
        // Declaration-only SDK files and debugger databases are not runtime
        // dependencies. Retain JavaScript, TypeScript source and license texts.
        if (/\.(?:map|pdb|d\.(?:ts|mts|cts))$/.test(name) || (allow && !allow.has(name) && !notice)) continue;
        const bytes = readFileSync(join(from, entry.name));
        const native = bytes.subarray(0, 4).equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46])) || /\.(node|dll|exe|dylib)$/.test(name);
        if (native && !name.endsWith('.node')) continue;
        mkdirSync(dirname(join(to, entry.name)), { recursive: true });
        // Native modules resolve through the installed-library map, never from writable data.
        writeFileSync(join(to, entry.name), native ? Buffer.alloc(0) : bytes);
      }
    }
  }
  copyTree(directory, target);
  if (typeof manifest.main === 'string' && ![manifest.main, manifest.main + '.js', manifest.main + '.json', join(manifest.main, 'index.js')].some(entry => existsSync(join(target, entry)))) {
    throw new Error(`Staging omitted package main: ${manifest.name}/${manifest.main}`);
  }
  for (const name of Object.keys({ ...manifest.dependencies, ...manifest.peerDependencies })) {
    if (manifest.peerDependenciesMeta?.[name]?.optional && !manifest.dependencies?.[name]) continue;
    edges.push([target, name, include(findDependency(directory, name))]);
  }
  for (const name of Object.keys(manifest.optionalDependencies ?? {})) {
    let dependency;
    try { dependency = findDependency(directory, name); } catch { continue; }
    const optional = JSON.parse(readFileSync(join(dependency, 'package.json'), 'utf8'));
    if (optional.os && !optional.os.includes('android') && !optional.os.includes('!win32')) continue;
    edges.push([target, name, include(dependency)]);
  }
  return target;
}
const closure = JSON.parse(readFileSync(join(upstream, 'python/sdk-runtime/package.json'), 'utf8'));
for (const name of Object.keys(closure.dependencies)) edges.push([destination, name, include(findDependency(join(upstream, 'python/sdk-runtime'), name))]);
edges.push([destination, 'pnpm', include(findDependency(upstream, 'pnpm'))]);
for (const [directory, name, dependency] of edges) {
  const link = join(directory, 'node_modules', name);
  mkdirSync(dirname(link), { recursive: true });
  if (!existsSync(link)) symlinkSync(relative(dirname(link), dependency), link);
}
writeFileSync(join(destination, 'package.json'), JSON.stringify({ private: true, type: 'module' }) + '\n');
const flock = [...packages].find(([source]) => source.endsWith('/native/system/packages/entry'));
if (!flock) throw new Error('Runtime closure omits native flock');
writeFileSync(join(flock[1], 'lib/android-system.node'), '');
writeFileSync(join(destination, 'package-inventory.json'), JSON.stringify([...packages].map(([source, target]) => ({
  ...JSON.parse(readFileSync(join(source, 'package.json'), 'utf8')),
  path: relative(destination, target),
})), null, 2));
console.log(`Staged ${packages.size} packages from the pinned installed source closure`);
