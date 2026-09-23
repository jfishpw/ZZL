import { WebSocketServer } from 'ws';
import { one, run, now } from './db.js';
import { notifyPending, ackCommand } from './commands.js';
import { pushDeviceState } from './grants.js';

/** userId -> Set<WebSocket>（控制端，同一家长可多端登录） */
const parentSockets = new Map();
/** deviceId -> WebSocket（被控端，每台设备保持一条长连接） */
const childSockets = new Map();

const PING_INTERVAL_MS = 30_000;

function json(data) {
  return JSON.stringify(data);
}

function safeSend(socket, payload) {
  if (socket && socket.readyState === socket.OPEN) {
    try {
      socket.send(json(payload));
    } catch {
      /* 忽略写入失败，由 close 事件统一清理 */
    }
  }
}

/* ---------------- 对外发送能力 ---------------- */

/** 向某台被控端下发事件（离线时返回 false，调用方可据此落库等待补发） */
export function sendToDevice(deviceId, payload) {
  const socket = childSockets.get(Number(deviceId));
  if (!socket) return false;
  safeSend(socket, payload);
  return true;
}

/** 向某台设备的家长推送事件 */
export function sendToOwner(deviceId, payload) {
  const device = one('SELECT user_id FROM devices WHERE id = ?', Number(deviceId));
  if (!device) return false;
  return sendToUser(device.user_id, payload);
}

/** 向某个家长账号的所有在线端推送 */
export function sendToUser(userId, payload) {
  const set = parentSockets.get(Number(userId));
  if (!set || set.size === 0) return false;
  for (const socket of set) safeSend(socket, payload);
  return true;
}

export function isDeviceOnline(deviceId) {
  return childSockets.has(Number(deviceId));
}

export function onlineDeviceIds() {
  return [...childSockets.keys()];
}

/* ---------------- 连接建立 ---------------- */

export function setupWebSocket(fastify) {
  const wss = new WebSocketServer({ server: fastify.server, path: '/ws' });

  wss.on('connection', (socket, req) => {
    let claims;
    try {
      const url = new URL(req.url, 'http://localhost');
      const token = url.searchParams.get('token');
      if (!token) throw new Error('missing token');
      claims = fastify.jwt.verify(token);
    } catch {
      socket.close(4001, 'unauthorized');
      return;
    }

    socket.isAlive = true;
    socket.on('pong', () => {
      socket.isAlive = true;
    });

    if (claims.role === 'parent') {
      attachParent(socket, claims);
    } else if (claims.role === 'child') {
      attachChild(socket, claims);
    } else {
      socket.close(4001, 'unauthorized');
    }
  });

  // 保活：定期 ping，清理死连接
  const timer = setInterval(() => {
    for (const socket of wss.clients) {
      if (socket.isAlive === false) {
        socket.terminate();
        continue;
      }
      socket.isAlive = false;
      try {
        socket.ping();
      } catch {
        /* ignore */
      }
    }
  }, PING_INTERVAL_MS);

  wss.on('close', () => clearInterval(timer));

  return wss;
}

/* ---------------- 控制端 ---------------- */

function attachParent(socket, claims) {
  const userId = Number(claims.userId);
  if (!parentSockets.has(userId)) parentSockets.set(userId, new Set());
  parentSockets.get(userId).add(socket);

  socket.role = 'parent';
  socket.userId = userId;

  safeSend(socket, {
    type: 'ready',
    role: 'parent',
    serverTime: now(),
    onlineDevices: onlineDeviceIds(),
  });

  socket.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (msg.type === 'ping') {
      safeSend(socket, { type: 'pong', serverTime: now() });
    }
  });

  socket.on('close', () => {
    const set = parentSockets.get(userId);
    if (!set) return;
    set.delete(socket);
    if (set.size === 0) parentSockets.delete(userId);
  });
}

/* ---------------- 被控端 ---------------- */

function attachChild(socket, claims) {
  const deviceId = Number(claims.deviceId);
  const device = one('SELECT id, user_id, name FROM devices WHERE id = ?', deviceId);
  if (!device) {
    socket.close(4004, 'device not found');
    return;
  }

  // 同一设备重复连接时，踢掉旧连接
  const previous = childSockets.get(deviceId);
  if (previous && previous !== socket) {
    try {
      previous.close(4000, 'replaced by new connection');
    } catch {
      /* ignore */
    }
  }

  childSockets.set(deviceId, socket);
  socket.role = 'child';
  socket.deviceId = deviceId;

  markDeviceOnline(deviceId, true);

  safeSend(socket, {
    type: 'ready',
    role: 'child',
    deviceId,
    serverTime: now(),
  });

  // 上线即补齐：待执行指令的条数提示 + 当前设备状态（锁定与生效授权）。
  // 指令内容本身让设备主动拉取，避免长连接承载大载荷；
  // 状态则直接推，因为它很小且决定了"现在是否应该被拦"，越早到越好。
  try {
    notifyPending(deviceId);
    pushDeviceState(deviceId);
  } catch {
    /* 补发失败不影响连接建立，设备在 Connected 后仍会主动拉取 */
  }

  sendToUser(device.user_id, {
    type: 'device_status',
    deviceId,
    online: true,
    at: now(),
  });

  socket.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    handleChildMessage(device, msg);
  });

  socket.on('close', () => {
    if (childSockets.get(deviceId) === socket) {
      childSockets.delete(deviceId);
      markDeviceOnline(deviceId, false);
      sendToUser(device.user_id, {
        type: 'device_status',
        deviceId,
        online: false,
        at: now(),
      });
    }
  });
}

function handleChildMessage(device, msg) {
  switch (msg.type) {
    case 'ping':
      safeSend(childSockets.get(device.id), { type: 'pong', serverTime: now() });
      break;

    case 'status':
      // 被控端实时状态：前台应用、剩余额度、权限健康度
      run(
        `UPDATE devices
            SET foreground_pkg = COALESCE(?, foreground_pkg),
                health_json    = COALESCE(?, health_json),
                last_seen      = ?,
                online         = 1
          WHERE id = ?`,
        msg.foregroundPackage ?? null,
        msg.health ? JSON.stringify(msg.health) : null,
        now(),
        device.id,
      );
      sendToUser(device.user_id, {
        type: 'device_status',
        deviceId: device.id,
        online: true,
        foregroundPackage: msg.foregroundPackage ?? null,
        remainingMs: msg.remainingMs ?? null,
        at: now(),
      });
      break;

    case 'permission_lost':
      // 关键：无障碍/使用情况/设备管理器被取消，必须让家长立刻知道
      sendToUser(device.user_id, {
        type: 'permission_lost',
        deviceId: device.id,
        deviceName: device.name,
        missing: msg.missing ?? [],
        at: now(),
      });
      break;

    case 'command_result':
      // 被控端通过长连接回执（HTTP 回执是主路径，这里是更快的一条通道）。
      // 两条路径都走同一个 ackCommand，已终态的指令不会被重复改写。
      if (typeof msg.commandId === 'string') {
        ackCommand(msg.commandId, device.id, msg.status === 'done' ? 'done' : 'failed', msg.detail ?? null);
      }
      break;

    default:
      // usage_live / time_request 等将由后续里程碑接管
      sendToUser(device.user_id, { ...msg, deviceId: device.id, relayedAt: now() });
      break;
  }
}

function markDeviceOnline(deviceId, online) {
  run(
    'UPDATE devices SET online = ?, last_seen = ? WHERE id = ?',
    online ? 1 : 0,
    now(),
    deviceId,
  );
}
