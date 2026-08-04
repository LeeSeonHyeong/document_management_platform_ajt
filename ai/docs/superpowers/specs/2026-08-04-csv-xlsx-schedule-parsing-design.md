# CSV·XLSX 파싱 지원 — 설계

**티켓**: [S15P11B106-159](https://ssafy.atlassian.net/browse/S15P11B106-159) — 이미 있었다.
S15P11B106-118이 "파서 확장이 필요해 별도 처리"로 명시적으로 범위 제외했고, 그 뒤 이 티켓이
만들어졌으나 착수되지 않은 채 남아 있었다.
**발견**: 2026-08-04, 일정 원본문서 업로드에 CSV·XLSX 지원이 필요하다는 요청에서 시작.

## 1. 배경

`요구사항정의서.md`의 FR-DOC-003·NFR-FILE-001은 일정 원본문서가 TXT·MD·DOCX·PDF·CSV·XLSX를
지원해야 한다고 정한다. 백엔드(`ScheduleSourceService.java`)는 이미 CSV·XLSX 업로드를
받아 저장한다(`text/csv`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
MIME 검증 통과). **막혀 있는 곳은 AI 서버 안에 두 군데다**:

1. `ai/src/document_parser/parser.py`의 `SUPPORTED = frozenset({".txt", ".md", ".pdf", ".docx"})`
   — 파서 자체가 없다
2. `ai/src/wiki_api/routers/source_parse.py`의 `_ALLOWED_SUFFIXES["schedule"]` — 파서가
   생겨도 이 목록에 `.csv`·`.xlsx`를 안 넣으면 이 라우터가 그전에 400으로 막는다.
   `_ALLOWED_SUFFIXES["wiki"]`는 그대로 둔다 — FR-DOC-002·계약이 위키 원본에는 CSV·XLSX를
   허용하지 않는다

## 2. 범위 — 실제 근거로 좁힌다

브레인스토밍 초반에는 "여러 시트로 나뉜 문서", "표+자유 텍스트가 섞인 문서"까지 고려했으나,
저장소를 뒤져도 그런 요구나 샘플이 없었다:

- `docs/api/testfiles/sample-schedule.csv` — 유일하게 확인되는 실제 픽스처. 헤더 1행 +
  데이터 행으로 된 **표 하나**(`제목,시작,종료,장소,대상`).
- XLSX 샘플은 저장소 전체에 없음.
- 요구사항서·ERD·`schedule_extractor` 어디에도 다중 시트·혼합 문서에 대한 언급 없음.

**그래서 v1은 "헤더 1행 + 데이터 행으로 된 표 하나"만 지원한다.** 다중 시트·표+자유텍스트
혼합은 실제 요구가 나타나면 그때 확장한다(YAGNI) — 지금 만들면 근거 없는 코드가 된다.

## 3. 아키텍처

기존 패턴을 그대로 따른다 — `parser.py`가 확장자로 분기해 개별 파서를 부른다.

```
parser.py
  ├─ csv_parser.py   (신규)
  ├─ xlsx_parser.py  (신규)
  └─ tabular_markdown.py  (신규, 위 둘이 공유)
```

`tabular_markdown.py`를 따로 두는 이유는 "행·열 데이터 → 마크다운 표" 로직이 CSV와 XLSX에서
완전히 같기 때문이다 — 차이는 그 앞단(디코딩 vs 워크북 읽기)에만 있다.

pandas(`to_markdown`)는 쓰지 않는다. 무거운 의존성(pandas+tabulate)이 새로 생기고, 날짜
서식(`number_format`)이나 우리가 정한 인코딩 판별 순서를 pandas가 대신해주지 않는다.
신규 의존성은 `openpyxl` 하나뿐이다.

## 4. 데이터 흐름과 규칙

### CSV 인코딩 판별 — 추측이 아니라 검증

1. BOM(`EF BB BF`) 있으면 UTF-8 확정
2. UTF-8 엄격 디코딩 시도 — 성공하면 확정. UTF-8은 바이트 구조가 엄격해서 규칙에 안 맞는
   바이트가 있으면 예외가 난다 — 이건 "그럴듯해서 고른다"가 아니라 "안 맞으면 반드시 실패
   한다"는 검증이다
3. 실패하면 CP949 디코딩 — 한국어 엑셀이 내보내는 CSV의 실질적 유일한 대안이다
4. CP949도 실패하면 `ParseError("encoding_undetected", ...)` — 조용히 깨진 텍스트를 만들지
   않는다

`charset-normalizer` 같은 통계적 감지는 쓰지 않는다. 위 방식이 이 문제(UTF-8 vs CP949 양자
택일)에서는 더 결정적이다.

### CSV 파싱

`csv.reader`(stdlib)로 행을 나눈다. 쉼표가 포함된 인용 필드를 올바르게 처리하기 위해 수동
split이 아니라 이걸 쓴다.

### XLSX 파싱

`openpyxl.load_workbook(path, data_only=True)`로 **첫 시트만** 읽는다. 나머지 시트가 있으면
`ParseResult.warnings`에 시트 이름을 남긴다(조용히 버리지 않는다).

**날짜/시간은 셀 값의 타입이 아니라 `cell.number_format`으로 판단한다.** 실제로 스파이크에서
날짜만 있는 셀이 `datetime.datetime(..., 0, 0)`으로 돌아오는 것을 확인했다 — `datetime.time`
객체는 자정이어도 파이썬에서 falsy가 아니라서, 값 타입만 보면 날짜에 "00:00"이 잘못 붙는다.
서식 문자열에 `h`가 있으면 시간까지 표기하고, 없으면 날짜만 표기한다.

병합 셀은 병합 영역의 좌상단 값을 구성 셀 전체에 채운다(빈 칸으로 두면 LLM이 데이터 누락으로
오해할 수 있다).

### 공통 — 마크다운 표 렌더링

`list[list[str]]` → 마크다운 표. 첫 행을 헤더로 삼는다. 열 개수가 안 맞는 행은 짧은 쪽을
빈 문자열로 채운다. 셀 값에 줄바꿈이 있으면 공백으로 치환한다(마크다운 표 문법이 셀 안
줄바꿈을 못 담는다).

## 5. 오류 처리

기존 `ParseError(code, message)` 모델을 그대로 쓴다. 새 코드 3개:

| 코드 | 조건 |
| --- | --- |
| `encoding_undetected` | CSV가 UTF-8도 CP949도 아님 |
| `not_tabular` | 헤더 행이 없거나 파일이 완전히 빈 표로 보임 |
| `corrupt_workbook` | XLSX 파일 자체가 손상돼 `openpyxl`이 못 엶 |

`pdf_parser`가 하듯 예외를 파서 밖으로 던지지 않고 `ParseResult(error=...)`로 감싼다.

`source_parse.py`의 `_REQUEST_ERROR_CODES`(`unsupported_file_type`·`file_not_found`·
`decode_failed` — 이 셋은 400으로 나간다)에 위 3개 코드를 추가한다. 안 넣으면 "CSV
인코딩을 못 판별했다"·"표가 아니다" 같은 **요청 자체의 문제**가 500 `DOCUMENT_PARSE_FAILED`
(추출 실패, 즉 우리 쪽 문제로 보이는 상태)로 잘못 분류된다.

## 6. 테스트 계획

- CSV 인코딩 3종(UTF-8·UTF-8 BOM·CP949) 픽스처로 디코딩 정확성
- `docs/api/testfiles/sample-schedule.csv`를 그대로 픽스처로 써서 실제 계약 샘플 기준 통과
- XLSX 날짜만 있는 셀 / 날짜+시간 있는 셀 — 자정이 안 붙는지 (스파이크에서 실측한 회귀
  대상)
- XLSX 다중 시트 — 첫 시트만 쓰고 나머지는 `warnings`에 이름이 남는지
- 헤더만 있고 데이터 행 없는 파일, 완전히 빈 파일 — 각각 기대 에러 코드
- 병합 셀이 있는 XLSX — 병합 영역 전체에 값이 채워지는지

## 7. 범위 제외 (다음에 실제 요구가 나오면)

- 다중 시트 각각을 별도 표로 반영
- 한 시트 안에 표와 자유 텍스트가 섞인 문서
- CSV/XLSX 외 인코딩(EUC-KR 등 CP949와 다른 레거시 인코딩)
