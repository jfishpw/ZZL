import { one } from '../db.js';

/**
 * 校验「路径中的设备属于当前登录家长」。
 * 不存在返回 404，不属于返回 403 —— 两种情况都返回 null，调用方直接 return。
 */
export function ownedDevice(request, reply) {
  const deviceId = Number(request.params.id);
  if (!Number.isInteger(deviceId)) {
    reply.code(400).send({ error: 'invalid_device_id', message: '设备 ID 不合法' });
    return null;
  }

  const device = one('SELECT * FROM devices WHERE id = ?', deviceId);
  if (!device) {
    reply.code(404).send({ error: 'device_not_found', message: '设备不存在' });
    return null;
  }
  if (device.user_id !== request.claims.userId) {
    reply.code(403).send({ error: 'forbidden', message: '无权访问该设备' });
    return null;
  }
  return device;
}

/**
 * 校验「路径中的设备就是令牌所属设备」（被控端专用）。
 */
export function ownDeviceFromToken(request, reply) {
  const deviceId = Number(request.params.id);
  if (!Number.isInteger(deviceId)) {
    reply.code(400).send({ error: 'invalid_device_id', message: '设备 ID 不合法' });
    return null;
  }
  if (Number(request.claims.deviceId) !== deviceId) {
    reply.code(403).send({ error: 'forbidden', message: '设备令牌与目标设备不匹配' });
    return null;
  }

  const device = one('SELECT * FROM devices WHERE id = ?', deviceId);
  if (!device) {
    reply.code(404).send({ error: 'device_not_found', message: '设备不存在' });
    return null;
  }
  return device;
}

/**
 * 直接按令牌取设备（被控端专用，路径中不含设备 ID 的接口用）。
 *
 * 像 `/api/commands/pending`、`/api/device-state` 这类接口的"当前设备"
 * 完全由令牌决定，路径里再带一个 id 只会多一处可以不匹配的地方。
 */
export function deviceFromToken(request, reply) {
  const deviceId = Number(request.claims.deviceId);
  if (!Number.isInteger(deviceId)) {
    reply.code(401).send({ error: 'invalid_token', message: '设备令牌无效，请重新配对' });
    return null;
  }

  const device = one('SELECT * FROM devices WHERE id = ?', deviceId);
  if (!device) {
    reply.code(404).send({ error: 'device_not_found', message: '设备不存在，请重新配对' });
    return null;
  }
  return device;
}
