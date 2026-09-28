# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/ancillary-service/package.json services/ancillary-service/package-lock.json services/ancillary-service/tsconfig.json ./services/ancillary-service/
RUN --mount=type=cache,target=/root/.npm cd services/ancillary-service && npm ci --install-links
COPY services/ancillary-service/src ./services/ancillary-service/src
COPY services/ancillary-service/migrations ./services/ancillary-service/migrations
COPY services/ancillary-service/config ./services/ancillary-service/config
RUN cd services/ancillary-service && npx tsc --noEmit false --outDir dist

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/ancillary-service/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/ancillary-service/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/ancillary-service/dist ./dist
COPY --from=builder --chown=app:app /src/services/ancillary-service/migrations ./migrations
COPY --from=builder --chown=app:app /src/services/ancillary-service/config ./config
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 CATALOG_POLICY_FILE=/app/config/catalog.properties
EXPOSE 8080
CMD ["node", "./dist/main.js"]
