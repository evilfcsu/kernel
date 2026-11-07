// 把 tools/watch.sh 异或加密成 app/src/main/assets/stealth/watch.sh.enc
// 密钥必须与 Stealth.kt 里的 SCRIPT_KEY 一致
const fs = require('fs');
const path = require('path');
const KEY = Buffer.from('Evil-Su-2026-watch', 'utf8');
const src = path.join(__dirname, 'watch.sh');
const dst = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'stealth', 'watch.sh.enc');
const plain = fs.readFileSync(src);
const out = Buffer.alloc(plain.length);
for (let i = 0; i < plain.length; i++) out[i] = plain[i] ^ KEY[i % KEY.length];
fs.mkdirSync(path.dirname(dst), { recursive: true });
fs.writeFileSync(dst, out);
console.log('encrypted', plain.length, '->', out.length, 'bytes');
