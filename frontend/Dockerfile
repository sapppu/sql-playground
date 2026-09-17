# ---- build stage: Node toolchain (not shipped) ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# Baked in at build time; empty (default) keeps same-origin /api calls.
ARG VITE_API_URL=""
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

# ---- serve stage: static files + /api reverse proxy ----
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
# Substituted into nginx.conf by CMD below (plain sed: no entrypoint
# envsubst quirks, no escaping issues with nginx $variables).
ENV BACKEND_URL=http://backend:8081
CMD ["sh", "-c", "sed -i \"s|__BACKEND_URL__|$BACKEND_URL|g\" /etc/nginx/conf.d/default.conf && exec nginx -g 'daemon off;'"]
