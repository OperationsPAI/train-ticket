# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/offer-management/package.json services/offer-management/package-lock.json services/offer-management/tsconfig.json ./services/offer-management/
RUN --mount=type=cache,target=/root/.npm cd services/offer-management && npm ci --install-links
COPY services/offer-management/src ./services/offer-management/src
COPY services/offer-management/migrations ./services/offer-management/migrations
RUN cd services/offer-management && npx tsc --noEmit false --outDir dist

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/offer-management/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/offer-management/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/offer-management/dist ./dist
COPY --from=builder --chown=app:app /src/services/offer-management/migrations ./migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
CMD ["node", "./dist/main.js"]
