import { createHash, createHmac, hkdfSync, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import argon2 from 'argon2';

/** argon2id with OWASP-recommended parameters for interactive logins. */
export async function hashPassword(password: string): Promise<string> {
  return argon2.hash(password, { type: argon2.argon2id, memoryCost: 19 * 1024, timeCost: 2, parallelism: 1 });
}

export async function verifyPassword(hash: string | null, password: string): Promise<boolean> {
  if (!hash) return false;
  try {
    return await argon2.verify(hash, password);
  } catch {
    return false;
  }
}

/** 256-bit opaque token, base64url encoded (43 chars). */
export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

export function sha256Hex(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

/** Crockford base32 alphabet: no I, L, O, U to avoid confusion when read aloud or typed. */
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

export function randomCrockfordCode(length = 10): string {
  let out = '';
  for (let i = 0; i < length; i++) out += CROCKFORD[randomInt(0, CROCKFORD.length)];
  return out;
}

/** Normalise a user-typed invitation code: uppercase, strip separators, map confusable letters. */
export function normaliseCrockford(input: string): string {
  return input.toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
}

export function deriveSubkey(secret: string, purpose: string): Buffer {
  return Buffer.from(hkdfSync('sha256', secret, '', purpose, 32));
}

export function hmacHex(key: Buffer, data: string): string {
  return createHmac('sha256', key).update(data, 'utf8').digest('hex');
}

export function safeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  try {
    return timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
  } catch {
    return false;
  }
}
