# DB 덤프 파일 보관 위치

이 디렉터리에 **최신 DB 덤프 파일**을 둔다.

| 파일 | 설명 |
| --- | --- |
| `ajt_dump_YYYYMMDD.sql` | 전체 덤프 (스키마 + 데이터) — **제출 필수** |
| `ajt_schema_YYYYMMDD.sql` | 스키마만 (선택) |
| `ajt_files_YYYYMMDD.tar.gz` | 업로드 파일 볼륨 백업 (선택) |

생성·복원 절차는 [../03_DB_덤프_가이드.md](../03_DB_덤프_가이드.md) 참고.

```bash
docker compose --project-name ajt-prod exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump \
    -uroot --default-character-set=utf8mb4 --single-transaction \
    --routines --triggers --events --set-gtid-purged=OFF ajt' \
  > exec/db/ajt_dump_$(date +%Y%m%d).sql
```

> ⚠️ 덤프 파일에는 회원 개인정보가 포함된다. 외부 공유에 주의한다.
> 스키마 정본은 [`docs/db/erd.sql`](../../docs/db/erd.sql) 이다.
