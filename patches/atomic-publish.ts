import { constants } from 'node:fs'
import { copyFile, link as posixLink, open, unlink } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { createRequire } from 'node:module'
import { join } from 'node:path'

/** Keep the source intact; atomically publish a fully synced independent file.
 * Android app domains reject hardlinks. RENAME_NOREPLACE preserves upstream's
 * collision semantics without exposing a partially copied destination. */
export async function link(source: string, target: string): Promise<void> {
  if (process.platform !== 'android') return posixLink(source, target)
  const runtime = process.env.DSH_ANDROID_RUNTIME
  if (runtime === undefined) throw new Error('Android runtime directory is unavailable')
  const binding = createRequire(import.meta.url)(join(runtime, 'android/android-posix.node')) as {
    publishExclusive(source: string, target: string): void
  }
  const temporary = `${target}.${randomBytes(16).toString('hex')}.android-publish`
  try {
    await copyFile(source, temporary, constants.COPYFILE_EXCL)
    const file = await open(temporary, 'r')
    try { await file.sync() } finally { await file.close() }
    binding.publishExclusive(temporary, target)
  } finally {
    await unlink(temporary).catch((error: unknown) => {
      if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
    })
  }
}
