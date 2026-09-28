# syntax=docker/dockerfile:1.7
FROM node:26-alpine AS builder
WORKDIR /src
COPY platform/ts-kit/package.json platform/ts-kit/package-lock.json platform/ts-kit/tsconfig.json ./platform/ts-kit/
COPY platform/ts-kit/src ./platform/ts-kit/src
RUN --mount=type=cache,target=/root/.npm cd platform/ts-kit && npm ci && npm run build
COPY services/customer-service/package.json services/customer-service/package-lock.json services/customer-service/tsconfig.json ./services/customer-service/
RUN --mount=type=cache,target=/root/.npm cd services/customer-service && npm ci --install-links
COPY services/customer-service/src ./services/customer-service/src
COPY services/customer-service/config ./services/customer-service/config
COPY services/customer-service/tools ./services/customer-service/tools
COPY services/customer-service/migrations ./services/customer-service/migrations
COPY services/admin-audit/config ./services/admin-audit/config
RUN cd services/customer-service && npx tsc --noEmit false --outDir dist
# The roster the image ships is rendered from the staffing statement the console publishes, so a
# release cannot answer a desk the console does not run and the names cannot be copied by hand and
# drift. The file it renders is the one the process resolves beside the module at start-up.
RUN node services/customer-service/tools/intake-desks.mjs services/admin-audit/config/intake-desks.json services/customer-service/config/intake-desks.json

FROM node:26-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /src/services/customer-service/package.json ./package.json
COPY --from=builder --chown=app:app /src/services/customer-service/node_modules ./node_modules
COPY --from=builder --chown=app:app /src/services/customer-service/dist ./dist
COPY --from=builder --chown=app:app /src/services/customer-service/config ./config
COPY --from=builder --chown=app:app /src/services/customer-service/migrations ./migrations
USER app
ENV PORT=8080 REDIS_URL=redis://localhost:6379
EXPOSE 8080
CMD ["node", "./dist/main.js"]
