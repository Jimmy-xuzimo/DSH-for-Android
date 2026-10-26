import { readFileSync, existsSync, realpathSync, mkdirSync, symlinkSync, lstatSync, renameSync, unlinkSync } from 'node:fs';
import { join, basename } from 'node:path';

const nativePath = process.env.DSH_ANDROID_NATIVE_DIR;
const runtimePath = process.env.DSH_ANDROID_RUNTIME;
if (!nativePath || !runtimePath) throw new Error('Android native runtime metadata is missing');
export const nativeDirectory = realpathSync(nativePath);
export const runtime = realpathSync(runtimePath);
const mapping = JSON.parse(readFileSync(join(runtime, 'android/native-map.json'), 'utf8'));
mkdirSync(join(runtime, 'bin'), { recursive: true });
for (const [command, filename] of Object.entries(mapping.commands)) {
  const destination = join(nativeDirectory, filename);
  if (!existsSync(destination)) throw new Error(`Missing packaged executable: ${command}`);
  const link = join(runtime, 'bin', command);
  const previous = lstatSync(link, { throwIfNoEntry: false });
  if (previous && !previous.isSymbolicLink()) throw new Error(`Unexpected executable entry: ${command}`);
  // Android changes nativeLibraryDir after every APK update. Replace stale links
  // atomically, including broken links left by removal of the previous install.
  if (!existsSync(link) || realpathSync(link) !== realpathSync(destination)) {
    const temporary = `${link}.${process.pid}.pending`;
    try { symlinkSync(destination, temporary); renameSync(temporary, link); }
    finally { try { unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; } }
  }
}
const originalDlopen = process.dlopen;
process.dlopen = function (module, filename, flags) {
  const relative = filename.startsWith(runtime + '/') ? filename.slice(runtime.length + 1) : undefined;
  const installed = relative && mapping.addons[relative];
  if (!installed) throw new Error(`Native extension is not packaged for Android: ${basename(filename)}`);
  const path = join(nativeDirectory, installed);
  return flags === undefined ? originalDlopen.call(this, module, path) : originalDlopen.call(this, module, path, flags);
};
