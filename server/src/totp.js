// TOTP (RFC 6238) 工具 —— 基于时间的一次性密码，兼容 Google Authenticator / Microsoft Authenticator
const crypto = require("crypto");

// 生成随机密钥（返回 base32 编码字符串）
function generateSecret(length = 20) {
  const bytes = crypto.randomBytes(length);
  return base32Encode(bytes);
}

// base32 编码（RFC 4648，无填充）
function base32Encode(buf) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0;
  let value = 0;
  let output = "";
  for (let i = 0; i < buf.length; i++) {
    value = (value << 8) | buf[i];
    bits += 8;
    while (bits >= 5) {
      output += alphabet[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) {
    output += alphabet[(value << (5 - bits)) & 31];
  }
  return output;
}

// base32 解码
function base32Decode(str) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  str = str.toUpperCase().replace(/[^A-Z2-7]/g, "");
  let bits = 0;
  let value = 0;
  const bytes = [];
  for (let i = 0; i < str.length; i++) {
    const idx = alphabet.indexOf(str[i]);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

// 生成当前时间步长（30秒）
function timeStep(step = 30) {
  return Math.floor(Date.now() / 1000 / step);
}

// 生成指定步长的 TOTP 码
function generateTOTP(secret, step = 30, digits = 6) {
  const counter = timeStep(step);
  return hotp(secret, counter, digits);
}

// HOTP (RFC 4226)
function hotp(secret, counter, digits = 6) {
  const key = base32Decode(secret);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter), 0);
  const hmac = crypto.createHmac("sha1", key).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const binary =
    ((hmac[offset] & 0x7f) << 24) |
    (hmac[offset + 1] << 16) |
    (hmac[offset + 2] << 8) |
    hmac[offset + 3];
  const otp = binary % Math.pow(10, digits);
  return otp.toString().padStart(digits, "0");
}

// 验证 TOTP 码（允许前后1个步长的容差，防止时钟偏差）
function verifyTOTP(secret, token, step = 30, digits = 6, window = 1) {
  if (!token || !/^\d{6}$/.test(String(token))) return false;
  const counter = timeStep(step);
  for (let i = -window; i <= window; i++) {
    const expected = hotp(secret, counter + i, digits);
    if (expected === String(token)) return true;
  }
  return false;
}

// 生成 otpauth:// URL（用于二维码扫码）
function otpauthUrl(secret, issuer = "EchoLink", account = "admin") {
  return `otpauth://totp/${encodeURIComponent(issuer)}:${encodeURIComponent(account)}?secret=${secret}&issuer=${encodeURIComponent(issuer)}&algorithm=SHA1&digits=6&period=30`;
}

module.exports = {
  generateSecret,
  generateTOTP,
  verifyTOTP,
  otpauthUrl,
  base32Encode,
  base32Decode,
};
