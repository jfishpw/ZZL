// 临时脚本：为 DNS 防护功能打服务端补丁（用完即删）
const fs = require('fs');

let t = fs.readFileSync('server/src/db.js', 'utf8');
const a = "  addColumnIfMissing('devices', 'icon_hidden', 'INTEGER NOT NULL DEFAULT 0');";
if (!t.includes(a)) throw new Error('anchor db');
t = t.replace(a, a + `
  // DNS 防护：家长期望的私人 DNS 主机名（空 = 不启用）与设备上报的实际状态
  addColumnIfMissing('devices', 'private_dns_host', "TEXT NOT NULL DEFAULT ''");
  addColumnIfMissing('devices', 'private_dns_active', "TEXT");`);
fs.writeFileSync('server/src/db.js', t);

t = fs.readFileSync('server/src/grants.js', 'utf8');
let s = 'uninstall_blocked, icon_hidden\n';
if (!t.includes(s)) throw new Error('anchor cols');
t = t.replace(s, 'uninstall_blocked, icon_hidden, private_dns_host\n');
s = '    iconHidden: !!device.icon_hidden,\n    stateVersion: device.state_version ?? 1,';
if (!t.includes(s)) throw new Error('anchor state');
t = t.replace(s, `    iconHidden: !!device.icon_hidden,
    /**
     * DNS 防护：家长期望设备使用的私人 DNS 主机名（null = 不启用）。
     * 与图标隐藏同一套状态对账机制：持续状态、重启不丢、改了立即推送。
     */
    privateDnsHost: device.private_dns_host || null,
    stateVersion: device.state_version ?? 1,`);
s = "    hidden ? 'device.icon_hidden' : 'device.icon_shown',\n    { delivered },\n  );\n  return { delivered };\n}";
if (!t.includes(s)) throw new Error('anchor setIconHidden');
t = t.replace(s, s + `

/** 设置/清除设备的私人 DNS 主机名（DNS 防护开关） */
export function setPrivateDns(deviceId, enabled, host, userId) {
  const target = enabled && host ? String(host).trim() : '';
  run('UPDATE devices SET private_dns_host = ? WHERE id = ?', target, Number(deviceId));
  bumpStateVersion(deviceId);
  const delivered = pushDeviceState(deviceId);

  writeAudit(
    userId,
    Number(deviceId),
    target ? 'device.private_dns_on' : 'device.private_dns_off',
    { delivered, host: target || undefined },
  );
  return { delivered, host: target };
}`);
fs.writeFileSync('server/src/grants.js', t);

t = fs.readFileSync('server/src/routes/commands.js', 'utf8');
s = '      locked: !!device.locked,\n      iconHidden: !!device.icon_hidden,\n    };';
if (!t.includes(s)) throw new Error('anchor pending');
t = t.replace(s, '      locked: !!device.locked,\n      iconHidden: !!device.icon_hidden,\n      privateDnsHost: device.private_dns_host || null,\n    };');
s = "    };\n  });\n\n  /** 指令历史与状态（含已失效） */";
if (!t.includes(s)) throw new Error('anchor route');
t = t.replace(s, `    };
  });

  /**
   * 设置/清除被控端的私人 DNS（DNS 防护）。
   *
   * 与图标隐藏同一套状态对账机制：写 devices 表 + bumpStateVersion + 推送。
   * host 必须是合法主机名（DoT 规范不允许 IP），长度上限 100。
   */
  fastify.post('/api/devices/:id/private-dns', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const enabled = request.body?.enabled === true;
    const host = typeof request.body?.host === 'string' ? request.body.host.trim() : '';
    if (enabled && (!host || !/^[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?$/.test(host) || host.length > 100)) {
      return reply.code(400).send({ error: 'invalid_host', message: '主机名只能是字母/数字/点/连字符，长度 1-100' });
    }

    const { delivered } = setPrivateDns(device.id, enabled, host, request.claims.userId);
    return {
      ok: true,
      enabled,
      host: enabled ? host : null,
      delivered,
      notice: enabled
        ? 'DNS 防护已下发，设备将在对账时启用（约 1 分钟内）'
        : 'DNS 防护已关闭，设备将恢复默认解析',
    };
  });

  /** 指令历史与状态（含已失效） */`);
fs.writeFileSync('server/src/routes/commands.js', t);

t = fs.readFileSync('server/src/routes/devices.js', 'utf8');
const importMatch = /import \{([^}]*)\} from '\.\.\/grants\.js'/.exec(t);
if (!importMatch) throw new Error('grants import');
t = t.replace(importMatch[0], importMatch[0].replace(/\}(\s*)$/, '  setPrivateDns,\n}$1'));
s = "    iconHidden: !!device.icon_hidden,\n    createdAt: device.created_at,";
if (!t.includes(s)) throw new Error('anchor detail');
t = t.replace(s, `    iconHidden: !!device.icon_hidden,
    /** DNS 防护：配置值与设备上报的实际状态（供控制端展示） */
    privateDnsHost: device.private_dns_host || null,
    privateDnsActive: device.private_dns_active || null,
    createdAt: device.created_at,`);
s = "              uninstall_blocked = COALESCE(?, uninstall_blocked)\n        WHERE id = ?`,";
if (!t.includes(s)) throw new Error('anchor heartbeat sql');
t = t.replace(s, `              uninstall_blocked = COALESCE(?, uninstall_blocked),
              private_dns_active = COALESCE(?, private_dns_active)
        WHERE id = ?`,);
s = "      device.id,\n    );\n\n    sendToUser(device.user_id, {";
if (!t.includes(s)) throw new Error('anchor heartbeat args');
t = t.replace(s, "      hardening?.privateDnsActive === undefined ? null : hardening.privateDnsActive ?? null,\n      device.id,\n    );\n\n    sendToUser(device.user_id, {");
s = '      uninstallBlocked: !!device.uninstall_blocked,\n      pinReady: !!device.pin_ready,';
if (!t.includes(s)) throw new Error('anchor hardening resp');
t = t.replace(s, '      uninstallBlocked: !!device.uninstall_blocked,\n      privateDnsActive: device.private_dns_active || null,\n      pinReady: !!device.pin_ready,');
fs.writeFileSync('server/src/routes/devices.js', t);

const pkg = JSON.parse(fs.readFileSync('server/package.json', 'utf8'));
pkg.version = '0.2.2';
fs.writeFileSync('server/package.json', JSON.stringify(pkg, null, 2) + '\n');
console.log('server patched, version', pkg.version);
