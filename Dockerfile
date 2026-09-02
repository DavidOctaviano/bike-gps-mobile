FROM node:20-alpine

WORKDIR /app
COPY package.json ./
COPY apps/api ./apps/api

ENV NODE_ENV=production
ENV PORT=8080
EXPOSE 8080

USER node
CMD ["node", "apps/api/src/server.mjs"]
