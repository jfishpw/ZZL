const fs = require('fs');
const path = require('path');

const root = 'D:/pwg/监管软件/server';

console.log('== server 目录 ==');
try {
  for (const f of fs.readdirSync(root)) console.log('  ' + f);
} catch (e) {
  console.log('  读取失败: ' + e.message);
}

console.log('\n== node_modules ==');
const nm = path.join(root, 'node_modules');
if (fs.existsSync(nm)) {
  const entries = fs.readdirSync(nm);
  console.log('  条目数: ' + entries.length);
  const wanted = ['fastify', '@fastify', 'better-sqlite3', 'ws', 'retrofit'];
  for (const w of wanted) {
    console.log('  ' + w + ': ' + (entries.includes(w) ? '已安装' : '缺失'));
  }
  const bs3 = path.join(nm, 'better-sqlite3', 'build', 'Release', 'better_sqlite3.node');
  console.log('  better-sqlite3 原生模块: ' + (fs.existsSync(bs3) ? '已编译' : '未编译'));
} else {
  console.log('  node_modules 尚未创建');
}

console.log('\n== npm registry 连通性 ==');
const https = require('https');
const req = https.get('https://registry.npmjs.org/fastify', { timeout: 10000 }, (res) => {
  console.log('  HTTP ' + res.statusCode);
  res.destroy();
  process.exit(0);
});
req.on('timeout', () => { console.log('  超时'); req.destroy(); process.exit(0); });
req.on('error', (e) => { console.log('  失败: ' + e.message); process.exit(0); });
