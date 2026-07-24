# ── Stage 1: Build the React app ─────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci

COPY . .
RUN npm run build

# ── Stage 2: Serve via nginx ─────────────────────────────────────
FROM nginx:alpine

# Copy the built React app
COPY --from=build /app/dist /usr/share/nginx/html

# Copy nginx config that proxies /api and /ws to the backend
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 3000

CMD ["nginx", "-g", "daemon off;"]
