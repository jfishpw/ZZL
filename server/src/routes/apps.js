import { all, run, now } from '../db.js';
import { sendToUser } from '../ws.js';
import { enqueueCommand } from '../commands.js';
import { ownedDevice, ownDeviceFromToken } from './helpers.js';

const MAX_APPS = 1000;

export default async function appRoutes(fastify) {
  /**
   * 被控端：上报已安装应用清单（整份替换）。
   * 整份替换而非增量，是因为"应用被卸载"这类事件很难增量表达，
   * 且清单规模很小（通常几十到一两百条），全量上报代价可忽略。
   */
  fastify.post('/api/devices/:id/installed-apps', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = ownDeviceFromToken(request, reply);
    if (!device) return;

    const apps = request.body?.apps;
    if (!Array.isArray(apps)) {
      return reply.code(400).send({ error: 'invalid_body', message: '缺少 apps 数组' });
    }

    const updatedAt = now();
    run('DELETE FROM installed_apps WHERE device_id = ?', device.id);

    let accepted = 0;
    for (const item of apps.slice(0, MAX_APPS)) {
      const packageName = typeof item?.packageName === 'string' ? item.packageName.trim() : '';
      if (!packageName) continue;

      run(
        `INSERT OR IGNORE INTO installed_apps (device_id, package_name, app_label, is_system, updated_at)
         VALUES (?, ?, ?, ?, ?)`,
        device.id,
        packageName,
        typeof item.appLabel === 'string' ? item.appLabel.slice(0, 80) : null,
        item.isSystem ? 1 : 0,
        updatedAt,
      );
      accepted += 1;
    }

    sendToUser(device.user_id, {
      type: 'installed_apps_updated',
      deviceId: device.id,
      count: accepted,
      at: updatedAt,
    });

    return { ok: true, accepted };
  });

  /** 控制端：读取已安装应用清单，用于配置黑白名单与逐应用规则 */
  fastify.get('/api/devices/:id/installed-apps', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const rows = all(
      'SELECT package_name, app_label, is_system, updated_at FROM installed_apps WHERE device_id = ? ORDER BY app_label',
      device.id,
    );

    return {
      apps: rows.map((r) => ({
        packageName: r.package_name,
        appLabel: r.app_label,
        isSystem: !!r.is_system,
      })),
      lastReportedAt: rows.length > 0 ? Math.max(...rows.map((r) => r.updated_at)) : null,
    };
  });

  /**
   * 控制端：请求被控端重新上报清单。
   *
   * 走指令队列而不是直接推 WebSocket 事件：设备离线时请求不能丢，
   * 必须在其上线后补发 —— 家长点完按钮发现「什么都没发生」是最糟的体验。
   */
  fastify.post('/api/devices/:id/installed-apps/refresh', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const command = enqueueCommand(device.id, 'request_installed_apps', {}, {
      userId: request.claims.userId,
      auditAction: 'command.requestApps',
    });

    return { ok: true, delivered: command.delivered, commandId: command.commandId };
  });
}
