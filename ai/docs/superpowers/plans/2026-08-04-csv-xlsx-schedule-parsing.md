# CSV·XLSX 일정 문서 파싱 지원 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 일정 원본문서로 올라오는 CSV·XLSX(헤더 1행 + 데이터 행짜리 표 하나)를 Markdown 표로
파싱해 `POST /internal/v1/source-parses`(`sourceType=schedule`)가 200을 돌려주게 한다.

**Architecture:** 기존 `document_parser` 패턴(확장자 → 개별 파서 → `ParseResult`)을 그대로
따른다. CSV·XLSX가 공유하는 "행·열 → Markdown 표" 로직은 `tabular_markdown.py`에 모으고,
CSV는 `csv_parser.py`(stdlib `csv` + 자체 인코딩 판별), XLSX는 `xlsx_parser.py`(`openpyxl`)가
각자 앞단만 맡는다.

**Tech Stack:** Python 3.12, stdlib `csv`, `openpyxl`(신규 의존성), 기존 FastAPI 서버.

## Global Constraints

- v1은 "헤더 1행 + 데이터 행으로 된 표 하나"만 지원한다. 다중 시트·표+자유텍스트 혼합은
  범위 밖이다 (근거: [설계 문서](../specs/2026-08-04-csv-xlsx-schedule-parsing-design.md) §2).
- 일정(`schedule`) `sourceType`에만 CSV·XLSX를 허용한다. `wiki` `sourceType`은 그대로
  TXT·MD·PDF·DOCX만 허용한다 (FR-DOC-002).
- CSV 인코딩 판별은 통계적 추측이 아니라 결정적 순서로 한다: BOM → UTF-8 엄격 디코딩 →
  CP949 → 실패.
- XLSX 날짜·시간 표시는 셀 값의 타입이 아니라 `cell.number_format`으로 판단한다 (값 타입만
  보면 날짜만 있는 셀에 자정이 잘못 붙는다 — 스파이크로 실측한 버그).
- 새 오류 코드는 `pdf_parser`의 기존 코드(`corrupt_document`)를 재사용하고, 정말 새로
  필요한 코드(`encoding_undetected`, `not_tabular`)만 추가한다.
- 헤더만 있고 데이터 행이 없는 CSV·XLSX는 오류가 아니다 — 유효한 표(그냥 일정이 0건)로
  본다. 완전히 빈 파일/시트만 `not_tabular`다.

---

### Task 1: `openpyxl` 의존성 추가

**Files:**
- Modify: `ai/pyproject.toml`

**Interfaces:**
- Produces: `xlsx_parser.py`(Task 5)가 쓸 `openpyxl` 패키지

- [ ] **Step 1: `pyproject.toml`의 `dependencies`에 한 줄 추가**

```toml
dependencies = [
    "pymupdf>=1.26,<2",
    "python-docx>=1.2,<2",
    "openpyxl>=3.1,<4",
    "mcp>=1.2",
    ...
]
```

`python-docx>=1.2,<2` 바로 아래에 넣는다 — 파일 형식 파서 의존성끼리 묶어 둔다.

- [ ] **Step 2: 동기화**

Run: `uv sync`
Expected: `openpyxl`이 `.venv`에 설치됨. `uv run python -c "import openpyxl; print(openpyxl.__version__)"`가 버전을 찍는다.

- [ ] **Step 3: 커밋**

```bash
cd ai
git add pyproject.toml uv.lock
git commit -m "chore(document-parser): CSV·XLSX 파싱용 openpyxl 의존성 추가"
```

---

### Task 2: CSV 인코딩 판별 (`encoding.py`)

**Files:**
- Create: `ai/src/document_parser/encoding.py`
- Test: `ai/tests/parsing/test_encoding.py`

**Interfaces:**
- Produces: `decode_csv_bytes(raw: bytes) -> str` — 성공하면 디코딩된 문자열, 실패하면
  `ValueError`를 던진다. `csv_parser.py`(Task 3)가 이 예외를 잡아 `ParseError`로 바꾼다.

- [ ] **Step 1: 실패 테스트 작성**

```python
# ai/tests/parsing/test_encoding.py
import pytest

from document_parser.encoding import decode_csv_bytes


def test_utf8_bom_is_stripped_and_decoded():
    raw = b"\xef\xbb\xbf\xec\xa0\x9c\xeb\xaa\xa9,\xec\x9d\xbc\xec\xa0\x95\n"
    assert decode_csv_bytes(raw) == "제목,일정\n"


def test_plain_utf8_decodes_without_a_bom():
    text = "제목,시작,종료\n전사 워크샵,2026-08-05 14:00,2026-08-05 18:00\n"
    assert decode_csv_bytes(text.encode("utf-8")) == text


def test_cp949_falls_back_when_utf8_is_invalid():
    text = "제목,시작,종료\n전사 워크샵,2026-08-05 14:00,2026-08-05 18:00\n"
    assert decode_csv_bytes(text.encode("cp949")) == text


def test_neither_encoding_raises_value_error():
    # UTF-8 도 CP949 도 아닌 바이트열. 0x80 단독은 두 인코딩 모두에서 잘못된 시작
    # 바이트다.
    raw = b"\x80\x81\x82\x83"
    with pytest.raises(ValueError, match="인코딩을 판별할 수 없는 CSV입니다"):
        decode_csv_bytes(raw)
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd ai && uv run pytest tests/parsing/test_encoding.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'document_parser.encoding'`

- [ ] **Step 3: 구현**

```python
# ai/src/document_parser/encoding.py
"""CSV 바이트열의 인코딩을 결정적으로 판별한다 — 추측이 아니라 검증이다.

BOM 이 있으면 UTF-8 이 확정이다. 없으면 UTF-8 엄격 디코딩을 시도한다 — UTF-8 은
바이트 구조가 엄격해서 규칙에 안 맞는 바이트가 하나라도 있으면 반드시 예외가
난다. 그래서 이 시도가 성공하면 "그럴듯해서 골랐다"가 아니라 "검증을 통과했다"는
뜻이다. 실패하면 한국어 엑셀이 내보내는 CSV 의 실질적인 유일한 대안인 CP949 를
시도한다. 그것도 실패하면 조용히 깨진 텍스트를 만들지 않고 예외로 알린다.
"""

_UTF8_BOM = b"\xef\xbb\xbf"


def decode_csv_bytes(raw: bytes) -> str:
    if raw.startswith(_UTF8_BOM):
        return raw[len(_UTF8_BOM):].decode("utf-8")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        pass
    try:
        return raw.decode("cp949")
    except UnicodeDecodeError as exc:
        raise ValueError("인코딩을 판별할 수 없는 CSV입니다") from exc
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd ai && uv run pytest tests/parsing/test_encoding.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: 커밋**

```bash
cd ai
git add src/document_parser/encoding.py tests/parsing/test_encoding.py
git commit -m "feat(document-parser): CSV 인코딩 결정적 판별(BOM/UTF-8/CP949) 추가"
```

---

### Task 3: 표 → Markdown 렌더러 (`tabular_markdown.py`)

**Files:**
- Create: `ai/src/document_parser/tabular_markdown.py`
- Test: `ai/tests/parsing/test_tabular_markdown.py`

**Interfaces:**
- Consumes: 없음 (순수 함수, 앞 태스크와 무관)
- Produces: `rows_to_markdown(rows: list[list[str]]) -> str` — `rows`가 빈 리스트면
  `ValueError`를 던진다. `csv_parser.py`(Task 4)·`xlsx_parser.py`(Task 5)가 이 함수와
  그 `ValueError`를 각자의 `not_tabular` 판정에 쓴다.

- [ ] **Step 1: 실패 테스트 작성**

```python
# ai/tests/parsing/test_tabular_markdown.py
import pytest

from document_parser.tabular_markdown import rows_to_markdown


def test_renders_a_header_and_two_data_rows():
    rows = [["제목", "시작", "종료"],
            ["전사 워크샵", "2026-08-05 14:00", "2026-08-05 18:00"],
            ["스프린트 회의", "2026-08-07 10:00", "2026-08-07 11:00"]]

    markdown = rows_to_markdown(rows)

    assert markdown == (
        "| 제목 | 시작 | 종료 |\n"
        "| --- | --- | --- |\n"
        "| 전사 워크샵 | 2026-08-05 14:00 | 2026-08-05 18:00 |\n"
        "| 스프린트 회의 | 2026-08-07 10:00 | 2026-08-07 11:00 |"
    )


def test_header_only_is_a_valid_table():
    assert rows_to_markdown([["제목", "시작"]]) == "| 제목 | 시작 |\n| --- | --- |"


def test_ragged_rows_are_padded_with_empty_cells():
    rows = [["제목", "시작", "종료"], ["워크샵", "2026-08-05"]]

    markdown = rows_to_markdown(rows)

    assert markdown.splitlines()[-1] == "| 워크샵 | 2026-08-05 |  |"


def test_embedded_newlines_become_spaces():
    rows = [["제목", "비고"], ["워크샵", "1부\n2부"]]

    markdown = rows_to_markdown(rows)

    assert markdown.splitlines()[-1] == "| 워크샵 | 1부 2부 |"


def test_no_rows_raises_value_error():
    with pytest.raises(ValueError, match="표로 인식할 내용이 없습니다"):
        rows_to_markdown([])
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd ai && uv run pytest tests/parsing/test_tabular_markdown.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: 구현**

```python
# ai/src/document_parser/tabular_markdown.py
"""표 데이터(행·열의 문자열) → Markdown 표. CSV·XLSX 파서가 공유한다.

둘의 차이는 앞단(디코딩 vs 워크북 읽기)에만 있고, "행 나열을 Markdown 표로 만든다"는
로직은 완전히 같다.
"""


def rows_to_markdown(rows: list[list[str]]) -> str:
    if not rows:
        raise ValueError("표로 인식할 내용이 없습니다")

    width = max(len(row) for row in rows)
    padded = [
        [_escape_cell(cell) for cell in row] + [""] * (width - len(row))
        for row in rows
    ]

    lines = ["| " + " | ".join(padded[0]) + " |",
             "| " + " | ".join(["---"] * width) + " |"]
    for row in padded[1:]:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def _escape_cell(value: str) -> str:
    """줄바꿈은 공백으로 바꾼다 — Markdown 표 문법이 셀 안 줄바꿈을 못 담는다."""
    return " ".join(value.splitlines())
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd ai && uv run pytest tests/parsing/test_tabular_markdown.py -v`
Expected: PASS (5 passed)

- [ ] **Step 5: 커밋**

```bash
cd ai
git add src/document_parser/tabular_markdown.py tests/parsing/test_tabular_markdown.py
git commit -m "feat(document-parser): 표 데이터를 Markdown 표로 렌더링하는 공유 함수 추가"
```

---

### Task 4: CSV 파서 (`csv_parser.py`)

**Files:**
- Create: `ai/src/document_parser/csv_parser.py`
- Test: `ai/tests/parsing/test_csv_parser.py`
- Reference fixture: `docs/api/testfiles/sample-schedule.csv` (저장소 루트 기준 — 실제 계약
  픽스처를 그대로 테스트에 쓴다)

**Interfaces:**
- Consumes: `decode_csv_bytes`(Task 2), `rows_to_markdown`(Task 3)
- Produces: `parse_csv(path: Path) -> ParseResult` — `document_parser/parser.py`(Task 6)가
  `.csv` 확장자에서 이 함수를 부른다.

- [ ] **Step 1: 실패 테스트 작성**

```python
# ai/tests/parsing/test_csv_parser.py
from pathlib import Path

from document_parser.csv_parser import parse_csv

REPO_ROOT = Path(__file__).resolve().parents[3]
SAMPLE_SCHEDULE_CSV = REPO_ROOT / "docs" / "api" / "testfiles" / "sample-schedule.csv"


def test_the_real_contract_fixture_parses_into_a_markdown_table():
    result = parse_csv(SAMPLE_SCHEDULE_CSV)

    assert result.error is None
    assert result.text == (
        "| 제목 | 시작 | 종료 | 장소 | 대상 |\n"
        "| --- | --- | --- | --- | --- |\n"
        "| 전사 워크샵 | 2026-08-05 14:00 | 2026-08-05 18:00 | 본사 대강당 | 전 직원 |\n"
        "| 스프린트 회의 | 2026-08-07 10:00 | 2026-08-07 11:00 | 회의실 A | 개발부 |"
    )


def test_cp949_encoded_csv_decodes_correctly(tmp_path: Path):
    text = "제목,시작,종료\n경비 정산 마감,2026-08-12 09:30,2026-08-12 10:00\n"
    path = tmp_path / "schedule.csv"
    path.write_bytes(text.encode("cp949"))

    result = parse_csv(path)

    assert result.error is None
    assert "경비 정산 마감" in result.text


def test_a_quoted_field_with_an_embedded_comma_is_one_cell(tmp_path: Path):
    text = '제목,장소\n워크샵,"본사, 3층 대강당"\n'
    path = tmp_path / "schedule.csv"
    path.write_text(text, encoding="utf-8")

    result = parse_csv(path)

    assert result.error is None
    assert "| 워크샵 | 본사, 3층 대강당 |" in result.text


def test_header_only_csv_is_not_an_error(tmp_path: Path):
    path = tmp_path / "schedule.csv"
    path.write_text("제목,시작,종료\n", encoding="utf-8")

    result = parse_csv(path)

    assert result.error is None
    assert result.text == "| 제목 | 시작 | 종료 |\n| --- | --- | --- |"


def test_an_empty_csv_is_not_tabular(tmp_path: Path):
    path = tmp_path / "empty.csv"
    path.write_text("", encoding="utf-8")

    result = parse_csv(path)

    assert result.error is not None
    assert result.error.code == "not_tabular"


def test_an_undetectable_encoding_is_reported(tmp_path: Path):
    path = tmp_path / "bad.csv"
    path.write_bytes(b"\x80\x81\x82\x83")

    result = parse_csv(path)

    assert result.error is not None
    assert result.error.code == "encoding_undetected"
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd ai && uv run pytest tests/parsing/test_csv_parser.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: 구현**

```python
# ai/src/document_parser/csv_parser.py
"""CSV → Markdown. 일정 원본문서에서만 쓴다 (위키 원본은 CSV를 허용하지 않는다)."""

import csv
import io
from pathlib import Path

from .encoding import decode_csv_bytes
from .models import PageResult, ParseError, ParseResult
from .tabular_markdown import rows_to_markdown


def parse_csv(path: Path) -> ParseResult:
    raw = path.read_bytes()
    try:
        text = decode_csv_bytes(raw)
    except ValueError:
        return ParseResult(
            error=ParseError("encoding_undetected", "인코딩을 판별할 수 없는 CSV입니다.")
        )

    rows = [row for row in csv.reader(io.StringIO(text)) if row]

    try:
        markdown = rows_to_markdown(rows)
    except ValueError:
        return ParseResult(
            error=ParseError("not_tabular", "표로 인식할 수 없는 CSV입니다.")
        )

    return ParseResult(
        text=markdown,
        pages=(PageResult(page=1, text=markdown, method="native", quality_score=1.0),),
        quality_score=1.0,
    )
```

`csv.reader(io.StringIO(text))`를 쓰는 이유: 인용된 필드 안에 줄바꿈이 있으면(여러 줄에
걸친 셀) `text.splitlines()`로 먼저 잘라버리면 그 필드가 두 행으로 쪼개진다. `StringIO`를
줘야 `csv.reader`가 필요할 때 다음 줄을 스스로 더 읽어서 인용된 필드를 하나로 되짚는다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd ai && uv run pytest tests/parsing/test_csv_parser.py -v`
Expected: PASS (6 passed)

- [ ] **Step 5: 커밋**

```bash
cd ai
git add src/document_parser/csv_parser.py tests/parsing/test_csv_parser.py
git commit -m "feat(document-parser): CSV 파서 추가 — 인코딩 판별 후 Markdown 표로 변환"
```

---

### Task 5: XLSX 파서 (`xlsx_parser.py`)

**Files:**
- Create: `ai/src/document_parser/xlsx_parser.py`
- Test: `ai/tests/parsing/test_xlsx_parser.py`

**Interfaces:**
- Consumes: `rows_to_markdown`(Task 3), `openpyxl`(Task 1)
- Produces: `parse_xlsx(path: Path) -> ParseResult` — `document_parser/parser.py`(Task 6)가
  `.xlsx` 확장자에서 이 함수를 부른다.

- [ ] **Step 1: 실패 테스트 작성**

```python
# ai/tests/parsing/test_xlsx_parser.py
import datetime
from pathlib import Path

from openpyxl import Workbook

from document_parser.xlsx_parser import parse_xlsx


def _save(tmp_path: Path, build) -> Path:
    wb = Workbook()
    build(wb)
    path = tmp_path / "schedule.xlsx"
    wb.save(path)
    return path


def test_a_single_sheet_table_becomes_a_markdown_table(tmp_path: Path):
    def build(wb):
        ws = wb.active
        ws.title = "일정"
        ws.append(["제목", "장소"])
        ws.append(["전사 워크샵", "본사 대강당"])
    path = _save(tmp_path, build)

    result = parse_xlsx(path)

    assert result.error is None
    assert result.text == (
        "| 제목 | 장소 |\n| --- | --- |\n| 전사 워크샵 | 본사 대강당 |"
    )


def test_a_date_only_cell_has_no_spurious_midnight(tmp_path: Path):
    """실측 버그: 값 타입(datetime.datetime)만 보면 날짜만 있는 셀도 자정이
    붙는다 — `datetime.time(0, 0)`이 파이썬에서 falsy 가 아니기 때문이다."""
    def build(wb):
        ws = wb.active
        ws.title = "일정"
        ws.append(["날짜", "제목"])
        ws.append([datetime.date(2026, 8, 10), "날짜만 있는 셀"])
        ws["A2"].number_format = "yyyy-mm-dd"
    path = _save(tmp_path, build)

    result = parse_xlsx(path)

    assert result.error is None
    assert "2026-08-10 |" in result.text
    assert "00:00" not in result.text


def test_a_datetime_cell_shows_both_date_and_time(tmp_path: Path):
    def build(wb):
        ws = wb.active
        ws.title = "일정"
        ws.append(["시작", "제목"])
        ws.append([datetime.datetime(2026, 8, 5, 14, 0), "워크샵"])
        ws["A2"].number_format = "yyyy-mm-dd hh:mm"
    path = _save(tmp_path, build)

    result = parse_xlsx(path)

    assert result.error is None
    assert "2026-08-05 14:00 |" in result.text


def test_a_merged_cell_repeats_its_value_across_the_range(tmp_path: Path):
    def build(wb):
        ws = wb.active
        ws.title = "안내"
        ws.append(["참가 대상", None])
        ws.merge_cells("A1:B1")
        ws.append(["부서", "인원"])
    path = _save(tmp_path, build)

    result = parse_xlsx(path)

    assert result.error is None
    assert "| 참가 대상 | 참가 대상 |" in result.text


def test_extra_sheets_are_reported_as_warnings_not_parsed(tmp_path: Path):
    def build(wb):
        ws = wb.active
        ws.title = "일정"
        ws.append(["제목"])
        wb.create_sheet("안내")
    path = _save(tmp_path, build)

    result = parse_xlsx(path)

    assert result.error is None
    assert result.warnings == ("시트 '안내'는 무시했습니다 — 첫 시트만 지원합니다.",)


def test_an_empty_first_sheet_is_not_tabular(tmp_path: Path):
    def build(wb):
        wb.active.title = "일정"
    path = _save(tmp_path, build)

    result = parse_xlsx(path)

    assert result.error is not None
    assert result.error.code == "not_tabular"


def test_a_corrupt_xlsx_is_reported(tmp_path: Path):
    path = tmp_path / "broken.xlsx"
    path.write_bytes(b"not a real xlsx")

    result = parse_xlsx(path)

    assert result.error is not None
    assert result.error.code == "corrupt_document"
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd ai && uv run pytest tests/parsing/test_xlsx_parser.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: 구현**

```python
# ai/src/document_parser/xlsx_parser.py
"""XLSX → Markdown. 첫 시트만 읽는다 (v1 범위 — 다중 시트는 근거 없음)."""

import datetime
from pathlib import Path

import openpyxl

from .models import PageResult, ParseError, ParseResult
from .tabular_markdown import rows_to_markdown


def parse_xlsx(path: Path) -> ParseResult:
    try:
        workbook = openpyxl.load_workbook(path, data_only=True)
    except Exception:
        return ParseResult(
            error=ParseError("corrupt_document", "XLSX 파일을 읽을 수 없습니다.")
        )

    sheet = workbook[workbook.sheetnames[0]]
    warnings = tuple(
        f"시트 '{name}'는 무시했습니다 — 첫 시트만 지원합니다."
        for name in workbook.sheetnames[1:]
    )

    rows = _sheet_rows(sheet)

    try:
        markdown = rows_to_markdown(rows)
    except ValueError:
        return ParseResult(
            error=ParseError("not_tabular", "표로 인식할 수 없는 XLSX입니다.")
        )

    return ParseResult(
        text=markdown,
        pages=(PageResult(page=1, text=markdown, method="native", quality_score=1.0),),
        quality_score=1.0,
        warnings=warnings,
    )


def _sheet_rows(sheet) -> list[list[str]]:
    merged_lookup = {}
    for merged_range in sheet.merged_cells.ranges:
        top_left = sheet.cell(merged_range.min_row, merged_range.min_col).value
        for row in range(merged_range.min_row, merged_range.max_row + 1):
            for col in range(merged_range.min_col, merged_range.max_col + 1):
                merged_lookup[(row, col)] = top_left

    rows = []
    for row in sheet.iter_rows():
        cells = [_cell_text(cell, merged_lookup) for cell in row]
        if any(cell for cell in cells):
            rows.append(cells)
    return rows


def _cell_text(cell, merged_lookup: dict) -> str:
    value = merged_lookup.get((cell.row, cell.column), cell.value)
    if value is None:
        return ""
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return _format_datetime(value, cell.number_format or "")
    return str(value)


def _format_datetime(value, number_format: str) -> str:
    """값의 타입이 아니라 셀 서식으로 날짜/시간 표시 여부를 정한다.

    `datetime.time(0, 0)`은 파이썬에서 falsy 가 아니라서, 값 타입만 보면 날짜만
    있는 셀(내부적으로 자정 시각이 채워진 `datetime.datetime`)에도 "00:00"이
    잘못 붙는다 — 스파이크에서 실측한 버그다. 서식 문자열에 실제로 시간 토큰(`h`)이
    있는지로 판단해야 정확하다.
    """
    fmt = number_format.lower()
    has_time = "h" in fmt
    has_date = any(token in fmt for token in "ymd")
    if has_date and has_time:
        return value.strftime("%Y-%m-%d %H:%M")
    if has_time:
        return value.strftime("%H:%M")
    return value.strftime("%Y-%m-%d")
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd ai && uv run pytest tests/parsing/test_xlsx_parser.py -v`
Expected: PASS (7 passed)

- [ ] **Step 5: 커밋**

```bash
cd ai
git add src/document_parser/xlsx_parser.py tests/parsing/test_xlsx_parser.py
git commit -m "feat(document-parser): XLSX 파서 추가 — 첫 시트만 Markdown 표로 변환"
```

---

### Task 6: `parser.py`에 두 확장자 연결

**Files:**
- Modify: `ai/src/document_parser/parser.py`
- Test: `ai/tests/parsing/test_parser_contract.py`

**Interfaces:**
- Consumes: `parse_csv`(Task 4), `parse_xlsx`(Task 5)
- Produces: `parse(path)`가 `.csv`·`.xlsx`도 처리 — `wiki_api/routers/source_parse.py`
  (Task 7)가 이 경로로 CSV·XLSX 요청을 흘려보낸다.

- [ ] **Step 1: 실패 테스트 작성**

```python
# ai/tests/parsing/test_parser_contract.py 에 추가
def test_csv_extension_is_supported(tmp_path: Path):
    path = tmp_path / "schedule.csv"
    path.write_text("제목\n워크샵\n", encoding="utf-8")

    result = parse(path)

    assert result.error is None
    assert "워크샵" in result.text


def test_xlsx_extension_is_supported(tmp_path: Path):
    from openpyxl import Workbook

    wb = Workbook()
    wb.active.append(["제목"])
    wb.active.append(["워크샵"])
    path = tmp_path / "schedule.xlsx"
    wb.save(path)

    result = parse(path)

    assert result.error is None
    assert "워크샵" in result.text
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd ai && uv run pytest tests/parsing/test_parser_contract.py -v`
Expected: FAIL — `result.error.code == "unsupported_file_type"` (아직 CSV·XLSX 미지원)

- [ ] **Step 3: 구현**

```python
# ai/src/document_parser/parser.py
from .csv_parser import parse_csv
from .docx_parser import parse_docx
from .models import ParseError, ParseOptions, ParseResult
from .pdf_parser import parse_pdf
from .text_parser import parse_text
from .xlsx_parser import parse_xlsx


SUPPORTED = frozenset({".txt", ".md", ".pdf", ".docx", ".csv", ".xlsx"})


def parse(file_path: str | Path, options: ParseOptions | None = None) -> ParseResult:
    options = options or ParseOptions()
    path = Path(file_path)

    if not path.is_file():
        return ParseResult(error=ParseError("file_not_found", "파일을 찾을 수 없습니다."))

    if path.suffix.lower() not in SUPPORTED:
        return ParseResult(
            error=ParseError("unsupported_file_type", "지원하지 않는 파일 형식입니다.")
        )

    if path.suffix.lower() in {".txt", ".md"}:
        return parse_text(path)

    if path.suffix.lower() == ".docx":
        return parse_docx(path, language=options.ocr_language)

    if path.suffix.lower() == ".pdf":
        return parse_pdf(path, language=options.ocr_language,
                         ocr_engine=options.ocr_engine)

    if path.suffix.lower() == ".csv":
        return parse_csv(path)

    if path.suffix.lower() == ".xlsx":
        return parse_xlsx(path)

    return ParseResult(
        error=ParseError("parser_unavailable", "해당 형식의 파서가 아직 연결되지 않았습니다.")
    )
```

(`Path` import는 파일 상단에 이미 있다 — 새로 추가하지 않는다.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd ai && uv run pytest tests/parsing/ -v`
Expected: PASS — 이번 태스크에서 추가한 2개 포함, 기존 것도 전부 그대로 통과

- [ ] **Step 5: `document_parser/__init__.py`는 그대로 둔다**

`parse`만 공개 API라 `__init__.py` 변경 불필요 — 확인만 한다.

Run: `cd ai && grep -n "csv_parser\|xlsx_parser" src/document_parser/__init__.py`
Expected: 아무 결과 없음 (그대로 둬야 맞다)

- [ ] **Step 6: 커밋**

```bash
cd ai
git add src/document_parser/parser.py tests/parsing/test_parser_contract.py
git commit -m "feat(document-parser): parser 디스패처에 CSV·XLSX 연결"
```

---

### Task 7: `source-parses` 엔드포인트가 일정용 CSV·XLSX를 허용

**Files:**
- Modify: `ai/src/wiki_api/routers/source_parse.py`
- Test: `ai/tests/api/test_source_parse.py`

**Interfaces:**
- Consumes: `parse()`(Task 6, 이미 CSV·XLSX 처리)
- Produces: 없음 (계약 경계의 마지막 단)

- [ ] **Step 1: 실패 테스트 작성**

```python
# ai/tests/api/test_source_parse.py 에 추가
def test_a_schedule_csv_source_is_accepted():
    csv_bytes = ("제목,시작,종료\n"
                 "전사 워크샵,2026-08-05 14:00,2026-08-05 18:00\n").encode("utf-8")

    response = _post(csv_bytes, "일정.csv", sourceType="schedule", sourceId="group-7",
                     originalFileName="일정.csv", mimeType="text/csv")

    assert response.status_code == 200, response.text
    assert "전사 워크샵" in response.json()["parsedMarkdown"]


def test_a_schedule_xlsx_source_is_accepted(tmp_path):
    from openpyxl import Workbook

    wb = Workbook()
    wb.active.append(["제목"])
    wb.active.append(["전사 워크샵"])
    path = tmp_path / "일정.xlsx"
    wb.save(path)

    response = _post(path.read_bytes(), "일정.xlsx", sourceType="schedule",
                     sourceId="group-7", originalFileName="일정.xlsx",
                     mimeType="application/vnd.openxmlformats-officedocument"
                              ".spreadsheetml.sheet")

    assert response.status_code == 200, response.text
    assert "전사 워크샵" in response.json()["parsedMarkdown"]


def test_a_wiki_csv_source_is_still_rejected():
    """FR-DOC-002 — 위키 원본은 CSV·XLSX 를 허용하지 않는다. 일정만 열었다."""
    response = _post(b"a,b\n1,2\n", "문서.csv", sourceType="wiki",
                     originalFileName="문서.csv", mimeType="text/csv")

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_SOURCE_PARSE_REQUEST"


def test_an_encoding_failure_is_a_bad_request_not_a_parse_failure():
    """인코딩을 못 판별한 것은 요청 자체의 문제다 — 500 이 아니라 400 이어야 한다."""
    response = _post(b"\x80\x81\x82\x83", "일정.csv", sourceType="schedule",
                     sourceId="group-7", originalFileName="일정.csv",
                     mimeType="text/csv")

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_SOURCE_PARSE_REQUEST"
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd ai && uv run pytest tests/api/test_source_parse.py -v`
Expected: FAIL — 앞 두 개는 400(`_ALLOWED_SUFFIXES`가 아직 CSV·XLSX를 안 받음), 마지막
하나는 500(`_REQUEST_ERROR_CODES`에 `encoding_undetected`가 없어 추출 실패로 분류됨)

- [ ] **Step 3: 구현**

```python
# ai/src/wiki_api/routers/source_parse.py
_ALLOWED_SUFFIXES = {
    "wiki": {".txt", ".md", ".pdf", ".docx"},
    "schedule": {".txt", ".md", ".pdf", ".docx", ".csv", ".xlsx"},
}

_REQUEST_ERROR_CODES = {"unsupported_file_type", "file_not_found", "decode_failed",
                        "encoding_undetected", "not_tabular"}
```

기존 주석("계약의 「정책」 절...")은 이제 사실과 다르므로 지운다 — CSV·XLSX가 더는
미지원이 아니다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd ai && uv run pytest tests/api/test_source_parse.py -v`
Expected: PASS — 새 테스트 4개 포함 전부 통과

- [ ] **Step 5: 전체 스위트 확인**

Run: `cd ai && uv run pytest -m "not ocr" -v`
Expected: 기존 실패 0. `ai/CLAUDE.md` 작업 수칙 — 늘어난 실패가 있는지 이 기준으로만 본다.

- [ ] **Step 6: 커밋**

```bash
cd ai
git add src/wiki_api/routers/source_parse.py tests/api/test_source_parse.py
git commit -m "feat(source-parse): 일정 원본문서에 CSV·XLSX 허용, 인코딩 오류를 400으로 분류"
```

---

## 계획 밖 — 실기동 확인 (구현 완료 후 별도로)

이 계획은 단위 테스트까지다. `ai/CLAUDE.md`의 "동작 확인은 근거를 구분해 말한다" 원칙에
따라, 실제 Spring이 `/api/v1/schedule-sources`로 CSV·XLSX를 업로드했을 때 이 경로 전체가
동작하는지는 **별도로 Spring+MySQL+AI 세 개를 띄워 실기동 확인해야 한다** — 이 계획의
범위가 아니다.
