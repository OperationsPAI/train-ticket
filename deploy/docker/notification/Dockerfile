# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/notification/package.json services/notification/package-lock.json services/notification/tsconfig.json ./services/notification/
RUN --mount=type=cache,target=/root/.npm cd services/notification && npm ci --install-links
COPY services/notification/src ./services/notification/src
COPY services/notification/migrations ./services/notification/migrations
RUN cd services/notification && npx tsc --noEmit false --outDir dist

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/notification/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/notification/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/notification/dist ./dist
COPY --from=builder --chown=app:app /src/services/notification/migrations ./migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
CMD ["node", "./dist/main.js"]
