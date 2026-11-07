# syntax=docker/dockerfile:1.7
FROM node:20.18-bookworm-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx prisma generate && npx tsc -p server/tsconfig.json && NEXT_TELEMETRY_DISABLED=1 npx next build

FROM node:20.18-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates curl && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=build /app/node_modules/.prisma ./node_modules/.prisma
COPY --from=build /app/node_modules/@prisma ./node_modules/@prisma
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/dist ./dist
COPY prisma ./prisma
COPY docker-entrypoint.sh /usr/local/bin/entrypoint
RUN chmod 0755 /usr/local/bin/entrypoint && mkdir -p /data/artifacts /data/builds /app/logs && chown -R node:node /data /app/logs
USER node
EXPOSE 3000 4000
HEALTHCHECK --interval=30s --timeout=4s --start-period=25s --retries=3 CMD curl -fsS http://127.0.0.1:4000/api/console/health || exit 1
ENTRYPOINT ["entrypoint"]
