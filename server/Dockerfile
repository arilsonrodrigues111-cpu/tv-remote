FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY . .
ENV PORT=80 DATA_DIR=/data
VOLUME /data
EXPOSE 80
CMD ["node", "server.js"]
