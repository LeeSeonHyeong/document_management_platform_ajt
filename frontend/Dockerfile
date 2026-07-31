# ===== 1단계: build — 소스를 정적 파일(dist)로 빌드 =====
FROM node:22-alpine AS build
WORKDIR /app

# 의존성 먼저 설치 (package.json 안 바뀌면 이 레이어는 캐시 재사용 → 빌드 빨라짐)
COPY package.json package-lock.json ./
RUN npm ci

# 나머지 소스 복사 후 빌드 → /app/dist 생성
COPY . .
RUN npm run build

# ===== 2단계: serve — nginx로 정적 파일 서빙 =====
FROM nginx:alpine

# 1단계에서 만든 정적 파일을 nginx 웹 루트로 복사
COPY --from=build /app/dist /usr/share/nginx/html

# SPA 라우팅용 커스텀 설정으로 교체
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 443
# nginx:alpine 은 기본으로 nginx를 포그라운드 실행하므로 ENTRYPOINT/CMD 불필요
