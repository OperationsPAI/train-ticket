# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/loyalty-membership/package.json services/loyalty-membership/package-lock.json services/loyalty-membership/tsconfig.json ./services/loyalty-membership/
RUN --mount=type=cache,target=/root/.npm cd services/loyalty-membership && npm ci --install-links
COPY services/loyalty-membership/src ./services/loyalty-membership/src
COPY services/loyalty-membership/migrations ./services/loyalty-membership/migrations
RUN cd services/loyalty-membership && npx tsc --noEmit false --outDir dist

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/loyalty-membership/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/loyalty-membership/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/loyalty-membership/dist ./dist
COPY --from=builder --chown=app:app /src/services/loyalty-membership/migrations ./migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
CMD ["node", "./dist/main.js"]
