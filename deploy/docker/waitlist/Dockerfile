# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/waitlist/package.json services/waitlist/package-lock.json services/waitlist/tsconfig.json ./services/waitlist/
RUN --mount=type=cache,target=/root/.npm cd services/waitlist && npm ci --install-links
COPY services/waitlist/src ./services/waitlist/src
COPY services/waitlist/migrations ./services/waitlist/migrations
RUN cd services/waitlist && npx tsc --noEmit false --rootDir src --outDir dist

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/waitlist/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/waitlist/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/waitlist/dist ./dist
COPY --from=builder --chown=app:app /src/services/waitlist/migrations ./migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379 MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
CMD ["node", "./dist/main.js"]
