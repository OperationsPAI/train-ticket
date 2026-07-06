# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
COPY platform/ts-kit/dist ./platform/ts-kit/dist
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/customer-service/package.json services/customer-service/package-lock.json services/customer-service/tsconfig.json ./services/customer-service/
RUN --mount=type=cache,target=/root/.npm cd services/customer-service && npm ci
COPY services/customer-service/src ./services/customer-service/src
RUN cd services/customer-service && npx tsc --noEmit false --outDir dist

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/customer-service/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/customer-service/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/customer-service/dist ./dist
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
CMD ["node", "-e", "import('./dist/bootstrap.js').then((m) => m.bootstrap()).catch((err) => { console.error(err); process.exit(1); })"]
