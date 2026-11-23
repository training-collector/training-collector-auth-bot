import { timingSafeEqual, createHash } from 'node:crypto';
import { send } from '@vercel/queue';

const digest = value => createHash('sha256').update(value).digest();

export async function POST(request) {
  const key = process.env.AUTH_SERVICE_KEY;
  if (!key || !timingSafeEqual(digest(request.headers.get('authorization') ?? ''), digest(`Bearer ${key}`))) {
    return new Response(null, { status: 403 });
  }
  await send('auth-delivery', {});
  return new Response(null, { status: 202 });
}
