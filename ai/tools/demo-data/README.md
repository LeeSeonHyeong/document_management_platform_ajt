# 시연 데이터 도구

발표 시연용 데이터셋을 만드는 로컬 전용 도구다. 설계는
`ai/docs/superpowers/specs/2026-08-08-demo-data-preparation-design.md`.

**원본을 지우지 않는다.** 로컬 `ajt` 와 `backend/build/ajt-documents` 는 읽기만 하고,
모든 작업은 복제본(`ajt_demo`, `backend/build/ajt-demo-documents`)에서 한다.

## 전제 조건

- `ajt-mysql` 컨테이너가 떠 있어야 한다
- 원본 파일 루트 `backend/build/ajt-documents` 가 이미 있어야 한다 (백엔드를 한 번 이상 돌린 상태)
- 아래 명령은 모두 `ai/` 디렉터리에서 실행한다

## 순서

```bash
cd ai
./tools/demo-data/clone.sh     # 복제
./tools/demo-data/check.sh     # 잔재가 검출된다 (정상)
./tools/demo-data/cleanup.sh   # QA 잔재 제거
./tools/demo-data/check.sh     # 통과
./tools/demo-data/org.sh       # 조직 재구성 적용 (가드 포함)
./tools/demo-data/check.sh     # 통과
```

## 백엔드를 복제본에 물려 띄우기

> ⚠️ **`SPRING_DATASOURCE_URL` 하나만 덮으면 시연 데이터가 지워진다.**
> 기본 프로파일이 `local` 이고 `application-local.yml` 에 `ddl-auto: create-drop` 과
> 샘플 시드(`ajt.local-data.enabled: true`)가 걸려 있다. datasource 만 MySQL 로 돌리면
> 기동할 때 `ajt_demo` 의 테이블을 전부 drop 하고 `[샘플]` 위키를 다시 넣는다.
> **아래처럼 `SPRING_APPLICATION_JSON` 으로 ddl-auto·local-data 까지 함께 덮는다.**

```bash
cd backend
SPRING_APPLICATION_JSON='{
  "spring": {
    "datasource": {
      "url": "jdbc:mysql://127.0.0.1:3306/ajt_demo?characterEncoding=UTF-8&serverTimezone=Asia/Seoul",
      "driver-class-name": "com.mysql.cj.jdbc.Driver",
      "username": "root", "password": "rootpw"
    },
    "jpa": { "hibernate": { "ddl-auto": "validate" } },
    "h2": { "console": { "enabled": false } }
  },
  "ajt": {
    "local-data": { "enabled": false },
    "super-admin": { "email": "seojun.lee@ajt.com" },
    "ai": { "internal-api-key": "local-dev-key" },
    "document": { "storage": { "root-path": "'"$(cd .. && pwd)"'/backend/build/ajt-demo-documents" } },
    "schedule": { "source-storage": { "root-path": "'"$(cd .. && pwd)"'/backend/build/ajt-demo-documents/schedule-sources" } }
  }
}' sh gradlew bootRun
```

기동 뒤 `ddl-auto` 가 `validate` 로 걸렸는지는 데이터 건수로 확인한다 —
`./tools/demo-data/check.sh` 의 지표가 기동 전과 같으면 정상이다.

AI 서버는 **`env -u` 로 셸에 주입된 Anthropic 변수를 걷어내고** 띄운다. 안 그러면
`src/.env` 가 덮여 GMS 대신 직접 API 로 나가고 크레딧이 샌다.

```bash
cd ai
env -u ANTHROPIC_BASE_URL -u ANTHROPIC_API_KEY uv run python -m wiki_api.serve --port 8000
```

## 되돌리기

```bash
docker exec ajt-mysql bash -c 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD -e "DROP DATABASE ajt_demo"'
rm -rf backend/build/ajt-demo-documents
```

## 확인 (2026-08-08)

정제·재구성을 마친 뒤 최신 develop(`c3e90ee`) 기준으로 전 스택을 띄워 확인했다.

**데이터**
- `check.sh` 6종 전부 통과, 종료 코드 0
- 지표: 위키 16 · 문서 21 · 계정 16 · 아웃링크 12 · 고아 문서 0
- 조직: 개발부·디자인부·인사부 각 ADMIN 1 · EMPLOYEE 4, 최고관리자 1 = 16명
- 원본 `ajt` 는 전 과정에서 위키 18 · 문서 53 · 계정 13 그대로, 파일 루트도 18M 유지

**기동**
- 백엔드가 `jdbc:mysql://127.0.0.1:3306/ajt_demo` 에 붙는 것을 로그로 확인
  (`HikariPool-1 - Added connection com.mysql.cj.jdbc.ConnectionImpl`)
- `ajt.local-data.enabled=false` 라 `[샘플]` 시드가 다시 들어가지 않는다 —
  이 값을 켜면 `pages/14.md`·`15.md` 류가 되살아나므로 시연 전에 반드시 꺼둔다
- 백엔드 자체 정합성 점검이 모두 "보정 대상 없음" 으로 통과:
  Wiki 관계 대칭성 · 부서장 지정 · 중단된 AI 작업
- 프론트(5173) 로그인 화면 정상 렌더, AI 서버(8000) 기동

**확인하지 못한 것**
- 로그인 이후 화면(위키 목록·링크·각주)은 보지 못했다. 비밀번호 입력이 필요해서다
- 위키 생성·챗봇은 돌리지 않았다. `src/.env` 에서 `ANTHROPIC_BASE_URL` 이 주석 처리돼
  있어 직접 Anthropic API 로 나가고, 실제 비용이 발생한다

**기동 시 함정**
- 백엔드 `health` 가 `DOWN` 으로 나오는 것은 SMTP 미설정 때문이다(`MailHealthIndicator`).
  데이터와 무관하다
- 기본 프로파일 `local` 은 H2 인메모리 + `ddl-auto: create-drop` + 샘플 시드다.
  MySQL 을 쓰려면 `SPRING_APPLICATION_JSON` 등으로 datasource·ddl-auto·local-data 를
  함께 덮어야 한다. 하나라도 빠지면 조용히 H2 로 뜬다

## 위키 본문을 손으로 고칠 때 (reindex.py)

위키 에이전트를 돌리면 비용이 들어, 시연 데이터의 본문 수정은 사람이 직접 한다.
그런데 본문만 고치면 `wiki.content_hash` 와 `wiki_search_chunk` 가 어긋난다 —
검색이 옛 내용을 찾고, 다음 에이전트 실행이 변경을 감지하지 못한다.

`reindex.py` 가 백엔드의 `WikiSearchIndexer.replace` 와 같은 결과를 만들어 그 둘을 맞춘다.

```bash
./tools/demo-data/reindex.py --all --verify        # 고치기 전에 재현이 정확한지 확인
./tools/demo-data/reindex.py --wiki 8 10 --apply   # 고친 뒤 재색인
./tools/demo-data/check.sh                         # 정합성 확인
```

**고치기 전에 반드시 `--verify` 를 돌린다.** 지금 DB 에 있는 청크와 이 스크립트가
재현한 청크가 완전히 일치해야 한다. 일치하지 않으면 자바 쪽 청킹 규칙이 바뀐 것이므로,
그대로 진행하면 조용히 어긋난 색인을 만든다.

본문 외에 함께 맞춰야 하는 것:

- 원본문서를 고쳤으면 `document.file_size` 도 실제 파일 크기로 갱신한다
- `original.md` 와 `parsed.md` 를 같은 내용으로 유지한다 (마크다운은 파싱본이 원문과 같다)
- 각주는 `[^n]: <문서 파일명>, <섹션 제목> — "<인용문>"` 형식이고, 인용문은 근거 문서에
  **연속으로 존재하는 문자열**이어야 한다. 떨어진 문장을 이어붙이지 않는다
