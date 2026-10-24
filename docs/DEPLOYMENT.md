# Deployment (recommended, not performed)

The API needs a **long-running process**: it holds WebSocket connections and runs the push outbox
and cleanup workers. Serverless functions are not suitable.

## Recommended shape
- One container (Node 26) on Fly.io, Railway, Render, or a small VPS (Hetzner/DigitalOcean) behind
  TLS. Start command: `node dist/server.js`. Health check: `GET /api/v1/health/ready`.
- MongoDB Atlas (M0 is sufficient for two users).
- Cloudflare R2 private bucket.
- Firebase project for FCM.

## Build
```bash
cd backend && pnpm install --frozen-lockfile && pnpm build
```
A minimal Dockerfile:
```Dockerfile
FROM node:26-alpine
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build
ENV NODE_ENV=production
CMD ["node", "dist/server.js"]
```

## Environment (production)
Set every variable from `backend/.env.example`; in particular `NODE_ENV=production`,
`STORAGE_DRIVER=r2` (the local driver is refused in production), a strong `JWT_SECRET`,
`CORS_ORIGINS` (can be empty for a native-only client), `PUBLIC_BASE_URL=https://api.example.com`.

## Android
Set `risaina.apiBaseUrl=https://api.example.com` in `android/local.properties` (or a CI secret)
before building the release APK. See `docs/RELEASE.md`.

## Scaling note
The design assumes one instance (D-04). If you ever run two, add a shared bus for WebSocket
fan-out (Redis pub/sub or MongoDB change streams) before scaling out.
