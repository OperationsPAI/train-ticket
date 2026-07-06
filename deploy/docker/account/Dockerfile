# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/account/package.json services/account/package-lock.json services/account/tsconfig.json ./services/account/
RUN --mount=type=cache,target=/root/.npm cd services/account && npm ci --install-links
COPY services/account/src ./services/account/src
RUN cd services/account && npx tsc --noEmit false --outDir dist

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/account/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/account/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/account/dist ./dist
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
CMD ["node", "-e", "import('./dist/bootstrap.js').then((m) => m.bootstrap()).catch((err) => { console.error(err); process.exit(1); })"]
