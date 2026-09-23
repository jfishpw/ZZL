import { randomBytes, scryptSync, pbkdf2Sync, timingSafeEqual, randomUUID } from 'node:crypto';

const SCRYPT_KEYLEN = 64;
const PBKDF2_ITERATIONS = 100_000;
const PBKDF2_KEYLEN = 32;

/* ---------------- 账号密码：scrypt ---------------- */

export function hashPassword(plain) {
  const salt = randomBytes(16);
  const key = scryptSync(plain, salt, SCRYPT_KEYLEN);
  return `scrypt$${salt.toString('base64')}$${key.toString('base64')}`;
}

export function verifyPassword(plain, stored) {
  return verifyHashed(plain, stored, 'scrypt');
}

/* ---------------- 离线密码：PBKDF2-HMAC-SHA256 ---------------- */

export function hashPin(pin) {
  const salt = randomBytes(16);
  const key = pbkdf2Sync(pin, salt, PBKDF2_ITERATIONS, PBKDF2_KEYLEN, 'sha256');
  return `pbkdf2$${PBKDF2_ITERATIONS}$${salt.toString('base64')}$${key.toString('base64')}`;
}

export function verifyPin(pin, stored) {
  return verifyHashed(pin, stored, 'pbkdf2');
}

/* ---------------- 通用校验 ---------------- */

function verifyHashed(plain, stored, expectedAlgo) {
  if (typeof stored !== 'string' || typeof plain !== 'string') return false;
  const parts = stored.split('$');
  if (parts[0] !== expectedAlgo) return false;

  try {
    let expected;
    let actual;

    if (expectedAlgo === 'scrypt') {
      const [, saltB64, keyB64] = parts;
      expected = Buffer.from(keyB64, 'base64');
      actual = scryptSync(plain, Buffer.from(saltB64, 'base64'), expected.length);
    } else {
      const [, iterStr, saltB64, keyB64] = parts;
      expected = Buffer.from(keyB64, 'base64');
      actual = pbkdf2Sync(plain, Buffer.from(saltB64, 'base64'), Number(iterStr), expected.length, 'sha256');
    }

    if (expected.length !== actual.length) return false;
    return timingSafeEqual(expected, actual);
  } catch {
    return false;
  }
}

/* ---------------- 配对码 ---------------- */

/** 生成 6 位数字配对码（排除易混淆的连续/重复数字体验问题，纯数字便于输入） */
export function generatePairCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

export function newUuid() {
  return randomUUID();
}

export function commandId() {
  return randomUUID();
}

/* ---------------- 防账号枚举 ---------------- */

/**
 * 预生成的假哈希。
 *
 * 登录时如果用户不存在，也要拿它走一遍等价开销的校验 ——
 * 否则"用户不存在"会比"密码错误"快一个数量级，攻击者可以据此枚举出哪些账号真实存在。
 * 预生成（而不是每次现算）也顺带避免了用登录接口做 CPU 耗尽攻击。
 */
const DUMMY_HASH = hashPassword('zzl-dummy-password-for-timing-equalization');

export function dummyHash() {
  return DUMMY_HASH;
}
