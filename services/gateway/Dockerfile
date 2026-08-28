FROM node:20-bookworm-slim
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY src ./src
ENV NODE_ENV=production
EXPOSE 8081
CMD ["node", "src/server.mjs"]
