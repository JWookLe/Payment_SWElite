FROM node:20-alpine AS build
WORKDIR /app

# 🔹 build-time ARG 선언
ARG VITE_API_BASE

# 🔹 ENV 설정 (Vite가 인식할 수 있도록)
ENV VITE_API_BASE=$VITE_API_BASE

# 🔹 필요한 파일 복사
COPY frontend/package.json frontend/vite.config.js frontend/index.html ./
COPY frontend/public ./public
COPY frontend/src ./src

# 🔹 빌드
RUN npm install && npm run build

# -------------------
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
