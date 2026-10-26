import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto'

const encoded = process.env.DSH_ANDROID_DATA_KEY
const key = encoded === undefined ? undefined : Buffer.from(encoded, 'base64')
delete process.env.DSH_ANDROID_DATA_KEY
if (key !== undefined && key.length !== 32) throw new Error('Invalid Android credentials key')
if (process.platform === 'android' && key === undefined) throw new Error('Android credentials key is required')
const prefix = 'DSH-ANDROID-CREDENTIALS-1:'

/** Authenticated encryption before the existing atomic-write and lock protocol. */
export function encryptCredentials(text: string): string {
  if (key === undefined) return text
  const nonce = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key, nonce)
  const ciphertext = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()])
  return prefix + Buffer.concat([nonce, cipher.getAuthTag(), ciphertext]).toString('base64')
}

/** Refuse damaged or plaintext stores rather than silently discarding credentials. */
export function decryptCredentials(text: string): string {
  if (key === undefined) return text
  if (!text.startsWith(prefix)) throw new Error('Credentials store is not encrypted for this Android installation')
  const bytes = Buffer.from(text.slice(prefix.length), 'base64')
  if (bytes.length < 28) throw new Error('Credentials store is truncated')
  const cipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12))
  cipher.setAuthTag(bytes.subarray(12, 28))
  return Buffer.concat([cipher.update(bytes.subarray(28)), cipher.final()]).toString('utf8')
}
