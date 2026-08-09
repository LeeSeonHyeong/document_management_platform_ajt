# DB 덤프 파일 보관 위치

이 디렉터리에 **최신 DB 덤프 파일**을 둔다.

| 파일 | 설명 |
| --- | --- |
| `ajt_dump_YYYYMMDD.sql` | 전체 덤프 (스키마 + 데이터) — **제출 필수** |
| `ajt_schema_YYYYMMDD.sql` | 스키마만 (선택) |
| `ajt_files_YYYYMMDD.tar.gz` | 업로드 파일 볼륨 백업 — **함께 넣는다** (아래 경고) |

생성·복원 절차는 [../03_DB_덤프_가이드.md](../03_DB_덤프_가이드.md) 참고.

```bash
docker compose --project-name ajt-prod exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump \
    -uroot --default-character-set=utf8mb4 --single-transaction \
    --routines --triggers --events --set-gtid-purged=OFF ajt' \
  > exec/db/ajt_dump_$(date +%Y%m%d).sql
```

> 회원·문서·일정 모두 가상 회사 **AJT** 의 시연용 데이터이며 실제 개인정보는 없다.
> 스키마 정본은 [`docs/db/erd.sql`](../../docs/db/erd.sql) 이다.

---

## 제출본 (2026-08-09)

| 파일 | 내용 |
| --- | --- |
| `ajt_dump_20260809.sql` | 전체 덤프 (스키마 17개 + 데이터), 384KB |
| `ajt_files_20260809.tar.gz` | 원본문서·위키 본문 파일, 3.7MB |

| | | | |
| --- | ---: | --- | ---: |
| 계정 | 32 | 일정 | 108 |
| 위키 | 35 | 문의 (답변) | 14 (8) |
| 문서 | 37 | 챗봇 질문 (출처) | 15 (25) |
| 검색 청크 | 258 | AI 작업 이력 | 14 |

공간(scope)은 전사 `ALL` 16장 · 개발부 `D1` 7장 · 디자인부 `D3` 6장 · 인사부 `D4` 6장이다.

두 파일 모두 배포 서버에서 만들었다. 아래 복원 절차를 빈 DB·빈 볼륨에 실제로 돌려
위 수치가 그대로 나오는 것을 확인했고, `DOCUMENT_STORAGE_ROOT` 가 `/data/ajt` 인 경우와
`/data/ajt/documents` 인 경우 양쪽에서 검증했다.

> 압축은 **리눅스에서 만든다.** macOS `tar` 로 만들면 확장 헤더가 들어가, 리눅스에서
> 풀 때 `._<이름>` AppleDouble 파일이 함께 생겨 위키 본문이 두 배로 세어진다.

`.tar.gz` 는 윈도우에서도 풀린다 — Windows 10 1803 이상이면 `tar` 명령이 기본 내장이다
(`tar -xzf ajt_files_20260809.tar.gz -C 대상경로`). 다만 **탐색기 더블클릭으로는 열리지 않으니**
명령이나 7-Zip 을 쓴다. 복원 대상은 리눅스 서버의 도커 볼륨이라 보통 서버에서 바로 푼다.

### 복원

DB 는 도커 볼륨 안 MySQL 에, 파일은 별도 볼륨에 있다. **둘 다 넣어야 한다.**

| 무엇 | 어디 | 볼륨 |
| --- | --- | --- |
| DB | `mysql` 컨테이너의 스키마 `ajt` | `ajt-prod-mysql-data` |
| 파일 | `backend` 컨테이너의 `/data/ajt` | `ajt-prod-files` |

DB 복원:

```bash
docker compose --project-name ajt-prod exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql \
    -uroot --default-character-set=utf8mb4 ajt' < ajt_dump_20260809.sql
```

파일은 압축 안에 `wiki/` 와 `schedule-sources/` 가 나란히 있는데 **둘의 기준 경로가 다르다.**
DB 는 원본문서를 `wiki/ALL/sources/12/original.md`(= `DOCUMENT_STORAGE_ROOT` 기준),
일정 원본을 `schedule-source-.../original/source.md`(= `SCHEDULE_SOURCE_STORAGE_ROOT` 기준)로
들고 있다. **한 곳에 통째로 풀면 일정 원본문서를 못 찾는다.** 두 경로를 컨테이너에서 읽어 각각 넣는다.

```bash
docker compose --project-name ajt-prod exec -T backend \
  printenv DOCUMENT_STORAGE_ROOT SCHEDULE_SOURCE_STORAGE_ROOT
# 위에서 읽은 두 경로를 아래 DOC / SCH 에 넣는다
DOC=/data/ajt ; SCH=/data/ajt/schedule-sources

mkdir -p ./_restore && tar -xzf ajt_files_20260809.tar.gz -C ./_restore
docker cp ./_restore/wiki            ajt-prod-backend-1:"$DOC"/
docker cp ./_restore/schedule-sources/. ajt-prod-backend-1:"$SCH"/
```

> **경로를 짐작하지 않는다.** 배포마다 다르게 잡혀 있다 — 운영은 `DOCUMENT_STORAGE_ROOT=/data/ajt`,
> 검증(develop)은 `/data/ajt/documents` 다. compose 기본값은 후자라서 운영 쪽이 환경파일에서
> 덮어쓰고 있다. 반드시 위 `printenv` 로 확인한 값을 쓴다.

> ⚠️ **SQL 과 파일을 반드시 함께 넣는다.**
> 위키의 제목·카테고리·관계는 DB 에 있지만 **본문은 파일에 있다**
> (`wiki/<공간>/pages/<해시>.md`). SQL 만 복원하면 위키 목록에 제목과 연결 문서만 뜨고
> **내용이 빈 채로 보인다.** 복원 후 컨테이너 안에서 위키 본문 `*/pages/*.md` 가 **35 개**,
> 일정 원본 `source.md` 가 **1 개** 세어져야 정상이다. 각각 `DOCUMENT_STORAGE_ROOT` 와
> `SCHEDULE_SOURCE_STORAGE_ROOT` 아래에서 센다.

### 계정

전 계정 비밀번호는 `password123!` 이다.

| 이름 | 이메일 | 역할 |
| --- | --- | --- |
| 이서준 | `superadmin@ajt.com` | 최고관리자 |
| 최우진 | `woojin.choi@ajt.com` | 개발부 관리자 |
| 한소율 | `soyul.han@ajt.com` | 디자인부 관리자 |
| 김도윤 | `doyun.kim@ajt.com` | 인사부 관리자 |
| 배성호 | `seongho.bae@ajt.com` | 개발부 사원 |

사원 27명이 더 있고 가입 승인 대기 3 · 거절 1 · 비활성 2 가 포함된다.

> **최고관리자는 이메일로 식별한다.** 백엔드 `ajt.super-admin.email`(환경변수
> `SUPER_ADMIN_EMAIL`, 기본값 `superadmin@ajt.com`)과 일치하는 계정만 최고관리자다. 설정을 바꾸면 DB 의 이메일도
> 함께 바꿔야 하며, 그러지 않으면 최고관리자가 0 명이 되어 위키 수정 대화와 가입 승인
> 메뉴가 화면에서 사라진다.
