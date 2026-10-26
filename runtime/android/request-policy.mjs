import { timingSafeEqual } from 'node:crypto';

export function authorizeRequest(request, origin, token, upgrade = false) {
  if (!origin || request.headers.host !== new URL(origin).host) return false;
  const suppliedOrigin = request.headers.origin;
  if (suppliedOrigin !== undefined && suppliedOrigin !== origin) return false;
  if (upgrade && suppliedOrigin !== origin) return false;
  if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method) && suppliedOrigin !== origin) return false;
  const cookies = String(request.headers.cookie ?? '').split(';').map(value => value.trim());
  const matching = cookies.filter(value => value.startsWith('dsh_android='));
  if (matching.length !== 1) return false;
  const supplied = Buffer.from(matching[0].slice('dsh_android='.length));
  const expected = Buffer.from(token);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}
