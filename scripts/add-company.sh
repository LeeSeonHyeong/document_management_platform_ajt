#!/usr/bin/env bash
# 새 회사 스택을 한 번에 생성한다.
#   - 전용 .env (비밀키·DB비번·포트)  → 전용 DB (완전 격리)
#   - docker compose 로 스택 배포      → 전용 포트 + HTTPS
#   - 관리자 계정 1개 시드 (부서 + ADMIN)
#
# 사용법: ./scripts/add-company.sh <회사이름(소문자)> [관리자이메일] [관리자비번]
#   포트는 기존 회사들 다음 번호로 자동 배정된다 (강제 지정:  PORT=8090 ./scripts/add-company.sh <회사>)
#   예:   ./scripts/add-company.sh companyc
#         ./scripts/add-company.sh companyd admin@d.com mypw123
#
# 사전 준비(최초 1회): sudo apt install -y apache2-utils   # htpasswd (BCrypt 해시 생성용)
set -euo pipefail

DOMAIN="i15b106.p.ssafy.io"

COMPANY="${1:?회사이름 필요 (예: companyc, 소문자)}"
ADMIN_EMAIL="${2:-admin@${COMPANY}.com}"
ADMIN_PW="${3:-$(openssl rand -hex 6)}"          # 안 주면 랜덤 생성
ENV_FILE=".env.${COMPANY}"

# --- 사전 체크 ---
[ -f docker-compose.yml ] || { echo "저장소 루트에서 실행하세요"; exit 1; }
command -v htpasswd >/dev/null || { echo "htpasswd 필요: sudo apt install -y apache2-utils"; exit 1; }

# --- 0) 포트 자동 배정: 기존 .env.* 의 최대 FRONTEND_PORT +1, 이미 쓰는 포트면 건너뜀 ---
# (강제 지정도 가능:  PORT=8090 ./scripts/add-company.sh <회사>)
if [ -z "${PORT:-}" ]; then
  LAST=$(grep -hE '^FRONTEND_PORT=' .env.* 2>/dev/null | cut -d= -f2 | grep -E '^[0-9]+$' | sort -n | tail -1)
  PORT=$([ -n "$LAST" ] && echo $((LAST + 1)) || echo "8081")
fi
while ss -ltn 2>/dev/null | grep -qE "[:.]${PORT}[[:space:]]"; do PORT=$((PORT + 1)); done
echo "[0/4] 포트 자동 배정: $PORT"

# --- 1) 전용 .env 생성 (회사마다 다른 비밀키·DB비번) ---
DB_PASS=$(openssl rand -hex 16)
ROOT_PASS=$(openssl rand -hex 16)
cat > "$ENV_FILE" <<EOF
SPRING_PROFILES_ACTIVE=prod
AJT_ACCESS_TOKEN_SECRET=$(openssl rand -base64 48)
AJT_PASSWORD_RESET_SECRET=$(openssl rand -base64 48)
AJT_AUTH_COOKIE_SECURE=true
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/ajt?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
SPRING_DATASOURCE_USERNAME=ajt
SPRING_DATASOURCE_PASSWORD=$DB_PASS
MYSQL_ROOT_PASSWORD=$ROOT_PASS
MYSQL_DATABASE=ajt
MYSQL_USER=ajt
MYSQL_PASSWORD=$DB_PASS
AI_BASE_URL=http://ai:8000
AI_INTERNAL_API_KEY=temp-dev-internal-key
DOCUMENT_STORAGE_ROOT=/data/ajt/documents
SCHEDULE_SOURCE_STORAGE_ROOT=/data/ajt/schedule-sources
INQUIRY_STORAGE_ROOT=/data/ajt/inquiries
FRONTEND_PORT=$PORT
COMPANY_ENV=$ENV_FILE
EOF
chmod 600 "$ENV_FILE"
echo "[1/4] $ENV_FILE 생성 (port=$PORT)"

# --- 2) 스택 배포 ---
docker compose -p "$COMPANY" --env-file "$ENV_FILE" up -d --build
echo "[2/4] 스택 배포 완료 (project=$COMPANY)"

# --- 3) MySQL 준비 대기 (스키마는 erd.sql 로 자동 로드됨) ---
echo -n "[3/4] MySQL 준비 대기"
until docker compose -p "$COMPANY" exec -T -e MYSQL_PWD="$ROOT_PASS" mysql \
        mysqladmin ping -h127.0.0.1 -uroot --silent >/dev/null 2>&1; do
  echo -n "."; sleep 2
done
echo " 준비됨"

# --- 4) 관리자 계정 시드 (부서 + ADMIN 멤버) ---
# BCrypt 해시 생성 (Spring BCryptPasswordEncoder 와 호환: $2y$ 도 검증됨)
HASH=$(htpasswd -bnBC 10 "" "$ADMIN_PW" | cut -d: -f2 | tr -d '\n')
docker compose -p "$COMPANY" exec -T -e MYSQL_PWD="$ROOT_PASS" mysql \
  mysql -uroot --default-character-set=utf8mb4 ajt <<SQL
INSERT INTO department (name) VALUES ('본사');
SET @dept = LAST_INSERT_ID();
INSERT INTO member (department_id, email, name, password_hash, role, signup_status, account_status)
VALUES (@dept, '${ADMIN_EMAIL}', '관리자', '${HASH}', 'ADMIN', 'APPROVED', 'ACTIVE');
SQL
echo "[4/4] 관리자 계정 시드 완료"

# --- 안내 ---
cat <<INFO

==================== ${COMPANY} 생성 완료 ====================
  URL     : https://${DOMAIN}:${PORT}
  관리자   : ${ADMIN_EMAIL}
  비밀번호 : ${ADMIN_PW}     <-- 지금 저장하세요 (다시 안 보여줌)

  방화벽 열기(최초 1회): sudo ufw allow ${PORT}
=============================================================
INFO
