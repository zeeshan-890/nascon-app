FROM node:20-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
COPY day2 ./day2
COPY seed ./seed
USER node
EXPOSE 8080
CMD ["node", "src/server.js"]
