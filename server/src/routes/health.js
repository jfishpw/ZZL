import { readFileSync } from 'node:fs';

import { now } from '../db.js';

const pkg = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8'));

export default async function healthRoutes(fastify) {
  fastify.get('/api/health', async () => ({
    ok: true,
    service: 'zhangzhongling',
    version: pkg.version,
    serverTime: now(),
  }));
}
