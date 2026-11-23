import { handleCallback, send } from '@vercel/queue';

export const POST = handleCallback(async (_, { messageId }) => {
  const response = await fetch(new URL('/internal/drain', process.env.AUTH_WORKER_URL), {
    method: 'POST',
    headers: { authorization: `Bearer ${process.env.AUTH_SERVICE_KEY}` },
    signal: AbortSignal.timeout(75_000),
  });
  if (!response.ok) throw new Error(`Auth worker returned HTTP ${response.status}`);
  const { delaySeconds } = await response.json();
  if (delaySeconds === null) return;
  if (!Number.isSafeInteger(delaySeconds) || delaySeconds < 0) throw new Error('Invalid outbox delay');
  await send('auth-delivery', {}, { delaySeconds, idempotencyKey: `after-${messageId}` });
}, { retry: (_, { deliveryCount }) => ({ afterSeconds: Math.min(300, 5 * 2 ** Math.min(deliveryCount, 6)) }) });
