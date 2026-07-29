> **이관 주석 (2026-07-28)** — 개인 검증 워크스페이스의 spike
> (`spikes/document-parsing-posthog/`)에서 작성된 기록을 그대로 옮겼다. 본문 경로는
> 당시 기준이다. 결과물은 이 저장소의 `ai/src/document_parser/` 와 `ai/tests/parsing/`
> 으로 들어왔다 (MR !17, S15P11B106-76).

# PostHog Document Parsing Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PostHog Markdown 3개를 TXT, PDF, DOCX와 스캔 변형으로 생성하고, LLM/API 토큰 없이 네이티브 텍스트 추출과 페이지 단위 OCR fallback의 정확도·실패 동작을 검증한다.

**Architecture:** 독립 `document_parser` 패키지가 파일 형식별 파서를 선택하고 공통 `ParseResult`를 반환한다. 공개 PostHog 원문과 결정론적으로 생성된 변형을 품질 평가기에 넣어 CER, 핵심 문구 검출률, OCR 사용 여부와 처리 시간을 Markdown 리포트로 만든다. 코퍼스 생성·평가 코드는 spike에 남기고 파서 패키지는 향후 백엔드 워커로 옮길 수 있도록 DB, 작업 큐, LLM에 의존하지 않는다.

**Tech Stack:** Python 3.12, uv, PyMuPDF, python-docx, ReportLab, Pillow, pytest, 로컬 Tesseract OCR(`eng`; 후속 실제 문서는 `kor+eng`)

---

## 사전 조건과 파일 지도

현재 `/mnt/c/Users/wolyong/workspace/llmwiki`는 Git 저장소가 아니다. 아래 커밋 단계는 상위 작업공간이 Git 저장소로 초기화되거나 이 spike가 저장소 안으로 이동된 경우에만 실행한다. 이 계획의 실행을 위해 임의로 Git 저장소를 초기화하지 않는다.

OCR 테스트에는 `tesseract`와 영어 traineddata가 필요하다. 설치 권한이 없다면 네이티브 파서 작업까지 진행하고 OCR 작업은 `ocr_unavailable` 테스트만 통과시킨 뒤, 실제 OCR 통합 테스트는 차단 사유로 보고한다. API 키와 LLM 토큰은 사용하지 않는다.

생성/수정 파일과 책임:

- `pyproject.toml`: Python 의존성, pytest 설정과 CLI entry point
- `.gitignore`: 생성 코퍼스, 비공개 문서, 결과 artifact 차단
- `README.md`: 환경 준비와 실행 방법
- `src/document_parser/models.py`: 공개 결과·페이지·오류 모델
- `src/document_parser/errors.py`: 내부 예외와 안정적인 오류 코드
- `src/document_parser/normalize.py`: 비교용 텍스트 정규화
- `src/document_parser/text_parser.py`: TXT·Markdown 처리
- `src/document_parser/pdf_parser.py`: PDF 네이티브 추출과 페이지별 OCR fallback
- `src/document_parser/docx_parser.py`: DOCX 문단·표 순서 추출과 이미지 전용 OCR
- `src/document_parser/ocr.py`: Tesseract 가용성 확인과 PyMuPDF OCR 어댑터
- `src/document_parser/parser.py`: 파일 형식 감지 및 공통 `parse()` 진입점
- `src/document_parser/quality.py`: CER·핵심 문구·순서 평가
- `src/document_parser/report.py`: Markdown 리포트 생성
- `scripts/prepare_posthog_corpus.py`: 기존 spike의 고정 원문 3개 복사와 provenance 기록
- `scripts/generate_variants.py`: TXT·PDF·DOCX·스캔 변형 생성
- `scripts/evaluate_corpus.py`: 전체 코퍼스 평가와 report 작성
- `tests/`: 각 공개 계약과 실패 동작의 TDD 테스트

### Task 1: 프로젝트 골격과 테스트 러너

**Files:**
- Create: `spikes/document-parsing-posthog/pyproject.toml`
- Create: `spikes/document-parsing-posthog/.gitignore`
- Create: `spikes/document-parsing-posthog/README.md`
- Create: `spikes/document-parsing-posthog/src/document_parser/__init__.py`
- Create: `spikes/document-parsing-posthog/tests/test_layout.py`

- [ ] **Step 1: 골격 검증 테스트 작성**

```python
# tests/test_layout.py
from pathlib import Path


ROOT = Path(__file__).parents[1]


def test_required_directories_exist():
    for relative in (
        "corpus/source/posthog",
        "corpus/generated",
        "corpus/expected",
        "corpus/private",
        "results/artifacts",
        "src/document_parser",
    ):
        assert (ROOT / relative).is_dir(), relative
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `cd spikes/document-parsing-posthog && uv run pytest tests/test_layout.py -v`

Expected: FAIL — 필수 디렉터리가 아직 없음.

- [ ] **Step 3: 패키지와 디렉터리 골격 작성**

```toml
# pyproject.toml
[project]
name = "posthog-document-parsing-spike"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
  "pymupdf>=1.26,<2",
  "python-docx>=1.2,<2",
  "reportlab>=4.4,<5",
  "pillow>=11,<13",
  "rapidfuzz>=3,<4",
]

[dependency-groups]
dev = ["pytest>=9,<10"]

[tool.pytest.ini_options]
testpaths = ["tests"]
markers = ["ocr: requires local Tesseract with eng traineddata"]
```

```gitignore
# .gitignore
.venv/
__pycache__/
.pytest_cache/
*.pyc
corpus/generated/**
!corpus/generated/.gitkeep
corpus/private/**
!corpus/private/.gitkeep
results/artifacts/**
!results/artifacts/.gitkeep
```

```python
# src/document_parser/__init__.py
"""Token-free document parsing spike."""
```

Create empty `.gitkeep` files under generated, private, and artifacts, plus the remaining required directories.

```markdown
# PostHog Document Parsing Spike

PostHog Markdown 문서를 여러 형식으로 변환한 뒤 네이티브 파싱과 로컬 OCR 품질을 검증한다. LLM 및 유료 OCR API를 호출하지 않는다.

## 준비

Python 3.12와 uv가 필요하다. OCR 테스트에는 Tesseract와 영어 traineddata가 추가로 필요하다.

```bash
uv sync
uv run pytest -m "not ocr"
```
```

- [ ] **Step 4: 골격 테스트 통과 확인**

Run: `cd spikes/document-parsing-posthog && uv sync && uv run pytest tests/test_layout.py -v`

Expected: `1 passed`.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog
git commit -m "chore: scaffold document parsing spike"
```

### Task 2: 공통 결과 계약과 디스패처

**Files:**
- Create: `spikes/document-parsing-posthog/src/document_parser/models.py`
- Create: `spikes/document-parsing-posthog/src/document_parser/errors.py`
- Create: `spikes/document-parsing-posthog/src/document_parser/parser.py`
- Modify: `spikes/document-parsing-posthog/src/document_parser/__init__.py`
- Create: `spikes/document-parsing-posthog/tests/test_parser_contract.py`

- [ ] **Step 1: 공개 계약의 실패 테스트 작성**

```python
# tests/test_parser_contract.py
from pathlib import Path

from document_parser import parse


def test_missing_file_returns_stable_error(tmp_path: Path):
    result = parse(tmp_path / "missing.pdf")
    assert result.text == ""
    assert result.pages == ()
    assert result.error is not None
    assert result.error.code == "file_not_found"


def test_unsupported_extension_returns_stable_error(tmp_path: Path):
    path = tmp_path / "sample.exe"
    path.write_bytes(b"MZ")
    result = parse(path)
    assert result.error is not None
    assert result.error.code == "unsupported_file_type"
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_parser_contract.py -v`

Expected: FAIL — `parse` 및 모델이 정의되지 않음.

- [ ] **Step 3: 불변 공개 모델과 디스패처 최소 구현**

```python
# src/document_parser/models.py
from dataclasses import dataclass
from typing import Literal


Method = Literal["native", "ocr"]


@dataclass(frozen=True)
class ParseError:
    code: str
    message: str
    failed_pages: tuple[int, ...] = ()


@dataclass(frozen=True)
class ParseOptions:
    ocr_language: str = "eng"


@dataclass(frozen=True)
class PageResult:
    page: int
    text: str
    method: Method
    quality_score: float


@dataclass(frozen=True)
class ParseResult:
    text: str = ""
    pages: tuple[PageResult, ...] = ()
    used_ocr: bool = False
    quality_score: float = 0.0
    warnings: tuple[str, ...] = ()
    error: ParseError | None = None
```

```python
# src/document_parser/errors.py
class DocumentParseFailure(Exception):
    def __init__(self, code: str, message: str, failed_pages: tuple[int, ...] = ()):
        super().__init__(message)
        self.code = code
        self.message = message
        self.failed_pages = failed_pages
```

```python
# src/document_parser/parser.py
from pathlib import Path

from .models import ParseError, ParseOptions, ParseResult


SUPPORTED = {".txt", ".md", ".pdf", ".docx"}


def parse(file_path: str | Path, options: ParseOptions | None = None) -> ParseResult:
    path = Path(file_path)
    options = options or ParseOptions()
    if not path.is_file():
        return ParseResult(error=ParseError("file_not_found", "파일을 찾을 수 없습니다."))
    if path.suffix.lower() not in SUPPORTED:
        return ParseResult(error=ParseError("unsupported_file_type", "지원하지 않는 파일 형식입니다."))
    return ParseResult(error=ParseError("parser_unavailable", "해당 형식의 파서가 아직 연결되지 않았습니다."))
```

```python
# src/document_parser/__init__.py
from .models import PageResult, ParseError, ParseOptions, ParseResult
from .parser import parse

__all__ = ["PageResult", "ParseError", "ParseOptions", "ParseResult", "parse"]
```

- [ ] **Step 4: 계약 테스트 통과 확인**

Run: `uv run pytest tests/test_parser_contract.py -v`

Expected: `2 passed`.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog/src spikes/document-parsing-posthog/tests/test_parser_contract.py
git commit -m "feat: define document parser result contract"
```

### Task 3: TXT·Markdown 파서와 정규화

**Files:**
- Create: `spikes/document-parsing-posthog/src/document_parser/normalize.py`
- Create: `spikes/document-parsing-posthog/src/document_parser/text_parser.py`
- Modify: `spikes/document-parsing-posthog/src/document_parser/parser.py`
- Create: `spikes/document-parsing-posthog/tests/test_text_parser.py`

- [ ] **Step 1: 정상 UTF-8과 디코딩 실패 테스트 작성**

```python
# tests/test_text_parser.py
from pathlib import Path

from document_parser import parse
from document_parser.normalize import normalize_text


def test_txt_and_markdown_are_read_as_utf8(tmp_path: Path):
    for suffix in (".txt", ".md"):
        path = tmp_path / f"policy{suffix}"
        path.write_text("# Time off\n\nMinimum 25 days.\n", encoding="utf-8")
        result = parse(path)
        assert result.error is None
        assert result.text == "# Time off\n\nMinimum 25 days.\n"
        assert result.pages[0].method == "native"
        assert result.quality_score == 1.0


def test_invalid_utf8_is_a_decode_failure(tmp_path: Path):
    path = tmp_path / "bad.txt"
    path.write_bytes(b"\xff\xfe\x00")
    result = parse(path)
    assert result.error is not None
    assert result.error.code == "decode_failed"


def test_normalization_is_stable():
    assert normalize_text(" A\r\n\r\nB\t C ") == "A\nB C"
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_text_parser.py -v`

Expected: FAIL — 텍스트 파서와 정규화 함수가 없음.

- [ ] **Step 3: 최소 구현**

```python
# src/document_parser/normalize.py
import re


def normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line).strip()
```

```python
# src/document_parser/text_parser.py
from pathlib import Path

from .models import PageResult, ParseError, ParseResult


def parse_text(path: Path) -> ParseResult:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return ParseResult(error=ParseError("decode_failed", "UTF-8 텍스트를 읽을 수 없습니다."))
    return ParseResult(
        text=text,
        pages=(PageResult(page=1, text=text, method="native", quality_score=1.0),),
        quality_score=1.0,
    )
```

Replace the final return in `parser.parse()` with:

```python
    if path.suffix.lower() in {".txt", ".md"}:
        from .text_parser import parse_text
        return parse_text(path)
    return ParseResult(error=ParseError("parser_unavailable", "해당 형식의 파서가 아직 연결되지 않았습니다."))
```

- [ ] **Step 4: 텍스트 테스트와 기존 계약 테스트 통과 확인**

Run: `uv run pytest tests/test_text_parser.py tests/test_parser_contract.py -v`

Expected: `5 passed`.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog/src spikes/document-parsing-posthog/tests/test_text_parser.py
git commit -m "feat: parse utf8 text and markdown documents"
```

### Task 4: 네이티브 DOCX 문단·표 순서 추출

**Files:**
- Create: `spikes/document-parsing-posthog/src/document_parser/docx_parser.py`
- Modify: `spikes/document-parsing-posthog/src/document_parser/parser.py`
- Create: `spikes/document-parsing-posthog/tests/test_docx_parser.py`

- [ ] **Step 1: 문단과 표의 문서 순서 테스트 작성**

```python
# tests/test_docx_parser.py
from pathlib import Path

from docx import Document

from document_parser import parse


def test_docx_preserves_paragraph_and_table_order(tmp_path: Path):
    path = tmp_path / "policy.docx"
    doc = Document()
    doc.add_heading("Time off", level=1)
    table = doc.add_table(rows=2, cols=2)
    table.cell(0, 0).text = "Type"
    table.cell(0, 1).text = "Minimum"
    table.cell(1, 0).text = "Paid leave"
    table.cell(1, 1).text = "25 days"
    doc.add_paragraph("Manager approval is not required.")
    doc.save(path)

    result = parse(path)

    assert result.error is None
    assert result.pages[0].method == "native"
    assert result.text.splitlines() == [
        "Time off",
        "Type | Minimum",
        "Paid leave | 25 days",
        "Manager approval is not required.",
    ]


def test_corrupt_docx_returns_stable_error(tmp_path: Path):
    path = tmp_path / "broken.docx"
    path.write_bytes(b"not a zip")
    result = parse(path)
    assert result.error is not None
    assert result.error.code == "corrupt_document"
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_docx_parser.py -v`

Expected: FAIL — DOCX가 아직 디스패치되지 않음.

- [ ] **Step 3: 네이티브 DOCX 최소 구현**

```python
# src/document_parser/docx_parser.py
from pathlib import Path
from zipfile import BadZipFile

from docx import Document
from docx.opc.exceptions import PackageNotFoundError
from docx.table import Table
from docx.text.paragraph import Paragraph

from .models import PageResult, ParseError, ParseResult


def _native_lines(path: Path) -> list[str]:
    document = Document(path)
    lines: list[str] = []
    for block in document.iter_inner_content():
        if isinstance(block, Paragraph):
            value = block.text.strip()
            if value:
                lines.append(value)
        elif isinstance(block, Table):
            for row in block.rows:
                values = [cell.text.strip().replace("\n", " ") for cell in row.cells]
                lines.append(" | ".join(values))
    return lines


def parse_docx(path: Path, language: str = "eng") -> ParseResult:
    try:
        lines = _native_lines(path)
    except (BadZipFile, PackageNotFoundError, ValueError, KeyError):
        return ParseResult(error=ParseError("corrupt_document", "DOCX 파일을 읽을 수 없습니다."))
    text = "\n".join(lines)
    if not text:
        return ParseResult(error=ParseError("native_extraction_empty", "DOCX에서 텍스트를 찾지 못했습니다."))
    return ParseResult(
        text=text,
        pages=(PageResult(1, text, "native", 1.0),),
        quality_score=1.0,
    )
```

Add before the final unavailable return in `parser.parse()`:

```python
    if path.suffix.lower() == ".docx":
        from .docx_parser import parse_docx
        return parse_docx(path, language=options.ocr_language)
```

- [ ] **Step 4: DOCX 테스트 통과 확인**

Run: `uv run pytest tests/test_docx_parser.py -v`

Expected: `2 passed`.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog/src spikes/document-parsing-posthog/tests/test_docx_parser.py
git commit -m "feat: extract ordered text and tables from docx"
```

### Task 5: PDF 네이티브 추출과 OCR 필요성 판정

**Files:**
- Create: `spikes/document-parsing-posthog/src/document_parser/pdf_parser.py`
- Modify: `spikes/document-parsing-posthog/src/document_parser/parser.py`
- Create: `spikes/document-parsing-posthog/tests/test_pdf_parser.py`

- [ ] **Step 1: 일반 PDF와 손상 PDF 테스트 작성**

```python
# tests/test_pdf_parser.py
from pathlib import Path

import pymupdf

from document_parser import parse
from document_parser.pdf_parser import needs_ocr


def _write_pdf(path: Path, text: str) -> None:
    doc = pymupdf.open()
    page = doc.new_page()
    page.insert_text((72, 72), text)
    doc.save(path)
    doc.close()


def test_native_pdf_is_extracted_without_ocr(tmp_path: Path):
    path = tmp_path / "policy.pdf"
    _write_pdf(path, "Minimum paid leave is 25 days.")
    result = parse(path)
    assert result.error is None
    assert "25 days" in result.text
    assert result.pages[0].method == "native"
    assert result.used_ocr is False


def test_short_or_replacement_heavy_text_needs_ocr():
    assert needs_ocr("") is True
    assert needs_ocr("abc") is True
    assert needs_ocr("Policy text with enough readable characters.") is False
    assert needs_ocr("�" * 20) is True


def test_corrupt_pdf_returns_stable_error(tmp_path: Path):
    path = tmp_path / "broken.pdf"
    path.write_bytes(b"not pdf")
    result = parse(path)
    assert result.error is not None
    assert result.error.code == "corrupt_document"
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_pdf_parser.py -v`

Expected: FAIL — PDF 파서가 없음.

- [ ] **Step 3: 네이티브 PDF와 휴리스틱 최소 구현**

```python
# src/document_parser/pdf_parser.py
from pathlib import Path

import pymupdf

from .models import PageResult, ParseError, ParseResult


def needs_ocr(text: str, minimum_chars: int = 20) -> bool:
    compact = "".join(text.split())
    if len(compact) < minimum_chars:
        return True
    return compact.count("�") / len(compact) > 0.10


def parse_pdf(path: Path, language: str = "eng") -> ParseResult:
    try:
        document = pymupdf.open(path)
    except (pymupdf.FileDataError, RuntimeError):
        return ParseResult(error=ParseError("corrupt_document", "PDF 파일을 읽을 수 없습니다."))

    pages: list[PageResult] = []
    failed: list[int] = []
    for index, page in enumerate(document, start=1):
        text = page.get_text("text", sort=True).strip()
        if needs_ocr(text):
            failed.append(index)
            continue
        pages.append(PageResult(index, text, "native", 1.0))
    document.close()

    if failed:
        return ParseResult(
            text="\n\f\n".join(page.text for page in pages),
            pages=tuple(pages),
            quality_score=len(pages) / max(len(pages) + len(failed), 1),
            warnings=("ocr_required",),
            error=ParseError("native_extraction_empty", "일부 페이지에 OCR이 필요합니다.", tuple(failed)),
        )
    text = "\n\f\n".join(page.text for page in pages)
    return ParseResult(text=text, pages=tuple(pages), quality_score=1.0)
```

Add before the final unavailable return in `parser.parse()`:

```python
    if path.suffix.lower() == ".pdf":
        from .pdf_parser import parse_pdf
        return parse_pdf(path, language=options.ocr_language)
```

- [ ] **Step 4: PDF 테스트 통과 확인**

Run: `uv run pytest tests/test_pdf_parser.py -v`

Expected: `3 passed`.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog/src spikes/document-parsing-posthog/tests/test_pdf_parser.py
git commit -m "feat: extract native pdf text and detect ocr pages"
```

### Task 6: 로컬 OCR 어댑터와 PDF 페이지 fallback

**Files:**
- Create: `spikes/document-parsing-posthog/src/document_parser/ocr.py`
- Modify: `spikes/document-parsing-posthog/src/document_parser/pdf_parser.py`
- Create: `spikes/document-parsing-posthog/tests/test_ocr.py`

- [ ] **Step 1: OCR 미설치와 실제 스캔 PDF 테스트 작성**

```python
# tests/test_ocr.py
from pathlib import Path

import pymupdf
import pytest
from PIL import Image, ImageDraw, ImageFont

from document_parser import parse
from document_parser.ocr import tesseract_available


def _write_scanned_pdf(path: Path, text: str) -> None:
    image = Image.new("RGB", (1800, 400), "white")
    font = ImageFont.load_default(size=54)
    ImageDraw.Draw(image).text((80, 120), text, fill="black", font=font)
    image_path = path.with_suffix(".png")
    image.save(image_path, dpi=(300, 300))
    doc = pymupdf.open()
    page = doc.new_page(width=600, height=134)
    page.insert_image(page.rect, filename=str(image_path))
    doc.save(path)
    doc.close()


def test_ocr_availability_returns_boolean():
    assert isinstance(tesseract_available("eng"), bool)


@pytest.mark.ocr
def test_scanned_pdf_uses_ocr(tmp_path: Path):
    if not tesseract_available("eng"):
        pytest.skip("Tesseract eng traineddata is not installed")
    path = tmp_path / "scan.pdf"
    _write_scanned_pdf(path, "Minimum paid leave is 25 days")
    result = parse(path)
    assert result.error is None
    assert result.used_ocr is True
    assert result.pages[0].method == "ocr"
    assert "25 days" in result.text
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_ocr.py -v`

Expected: FAIL — OCR 어댑터가 없음.

- [ ] **Step 3: OCR 어댑터와 PDF fallback 구현**

```python
# src/document_parser/ocr.py
import os
import shutil
import subprocess

import pymupdf

from .errors import DocumentParseFailure


def tesseract_available(language: str = "eng") -> bool:
    executable = shutil.which("tesseract")
    if executable is None:
        return False
    completed = subprocess.run(
        [executable, "--list-langs"],
        capture_output=True,
        text=True,
        check=False,
        env=os.environ.copy(),
    )
    available = {line.strip() for line in completed.stdout.splitlines()[1:]}
    return completed.returncode == 0 and all(item in available for item in language.split("+"))


def ocr_page(page: pymupdf.Page, language: str = "eng", dpi: int = 300) -> str:
    if not tesseract_available(language):
        raise DocumentParseFailure("ocr_unavailable", f"Tesseract 언어 데이터가 없습니다: {language}")
    try:
        text_page = page.get_textpage_ocr(language=language, dpi=dpi, full=True)
        return page.get_text("text", textpage=text_page, sort=True).strip()
    except RuntimeError as exc:
        raise DocumentParseFailure("ocr_failed", "페이지 OCR에 실패했습니다.") from exc
```

Replace `pdf_parser.py` with:

```python
# src/document_parser/pdf_parser.py
from pathlib import Path

import pymupdf

from .errors import DocumentParseFailure
from .models import PageResult, ParseError, ParseResult
from .ocr import ocr_page


def needs_ocr(text: str, minimum_chars: int = 20) -> bool:
    compact = "".join(text.split())
    if len(compact) < minimum_chars:
        return True
    return compact.count("�") / len(compact) > 0.10


def parse_pdf(path: Path, language: str = "eng") -> ParseResult:
    try:
        document = pymupdf.open(path)
    except (pymupdf.FileDataError, RuntimeError):
        return ParseResult(error=ParseError("corrupt_document", "PDF 파일을 읽을 수 없습니다."))

    pages: list[PageResult] = []
    failed: list[int] = []
    warnings: list[str] = []
    for index, page in enumerate(document, start=1):
        text = page.get_text("text", sort=True).strip()
        method = "native"
        quality = 1.0
        if needs_ocr(text):
            try:
                text = ocr_page(page, language=language)
                method = "ocr"
                quality = 0.95 if text else 0.0
            except DocumentParseFailure as exc:
                failed.append(index)
                warnings.append(exc.code)
                continue
        if not text:
            failed.append(index)
            warnings.append("low_quality")
            continue
        pages.append(PageResult(index, text, method, quality))
    document.close()

    combined = "\n\f\n".join(page.text for page in pages)
    used_ocr = any(page.method == "ocr" for page in pages)
    quality_score = sum(page.quality_score for page in pages) / max(len(pages), 1)
    if failed and pages:
        return ParseResult(
            text=combined,
            pages=tuple(pages),
            used_ocr=used_ocr,
            quality_score=quality_score,
            warnings=tuple(dict.fromkeys(warnings)),
            error=ParseError("partial_failure", "일부 PDF 페이지를 추출하지 못했습니다.", tuple(failed)),
        )
    if failed:
        code = warnings[0] if warnings else "ocr_failed"
        return ParseResult(
            warnings=tuple(dict.fromkeys(warnings)),
            error=ParseError(code, "PDF에서 텍스트를 추출하지 못했습니다.", tuple(failed)),
        )
    return ParseResult(
        text=combined,
        pages=tuple(pages),
        used_ocr=used_ocr,
        quality_score=quality_score,
    )
```

- [ ] **Step 4: 비-OCR 및 OCR 테스트 실행**

Run: `uv run pytest tests/test_pdf_parser.py tests/test_ocr.py -v`

Expected: 네이티브 테스트 PASS. Tesseract가 있으면 OCR 테스트 PASS, 없으면 OCR 통합 테스트 1개 SKIP.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog/src spikes/document-parsing-posthog/tests/test_ocr.py
git commit -m "feat: add local tesseract page fallback"
```

### Task 7: PostHog 원본 고정과 결정론적 변형 생성

**Files:**
- Create: `spikes/document-parsing-posthog/scripts/prepare_posthog_corpus.py`
- Create: `spikes/document-parsing-posthog/scripts/generate_variants.py`
- Create: `spikes/document-parsing-posthog/scripts/__init__.py`
- Create: `spikes/document-parsing-posthog/tests/test_corpus_generation.py`
- Create during execution: `spikes/document-parsing-posthog/corpus/source/posthog/*.md`
- Create during execution: `spikes/document-parsing-posthog/corpus/source/posthog/provenance.json`

- [ ] **Step 1: 코퍼스 고정 및 변형 manifest 테스트 작성**

```python
# tests/test_corpus_generation.py
import json
from pathlib import Path

from document_parser import parse
from scripts.generate_variants import generate_all
from scripts.prepare_posthog_corpus import prepare


ROOT = Path(__file__).parents[1]


def test_prepare_copies_exactly_three_sources():
    prepare()
    source = ROOT / "corpus/source/posthog"
    assert sorted(path.name for path in source.glob("*.md")) == [
        "company-security.md",
        "onboarding.md",
        "time-off-current.md",
    ]
    provenance = json.loads((source / "provenance.json").read_text(encoding="utf-8"))
    assert set(provenance["documents"]) == {
        "company-security.md", "onboarding.md", "time-off-current.md"
    }


def test_generate_all_writes_manifest_and_expected_variants():
    prepare()
    manifest = generate_all(seed=20260726)
    assert manifest["seed"] == 20260726
    assert {entry["variant"] for entry in manifest["files"]} == {
        "txt", "pdf", "docx", "scan", "rotated_scan", "low_res_scan", "mixed_pdf", "image_docx"
    }


def test_native_pdf_parsing_is_repeatable():
    prepare()
    manifest = generate_all(seed=20260726)
    item = next(entry for entry in manifest["files"] if entry["source"] == "time-off-current.md" and entry["variant"] == "pdf")
    path = ROOT / "corpus/generated" / item["path"]
    first = parse(path)
    second = parse(path)
    assert first.text == second.text
    assert first.pages == second.pages
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_corpus_generation.py -v`

Expected: FAIL — 코퍼스 스크립트가 없음.

- [ ] **Step 3: 기존 고정 코퍼스 복사 스크립트 작성**

`prepare_posthog_corpus.py`는 `spikes/deepagents-lucas-llmwiki/results/corpus/current/`에서 지정된 3개 파일을 복사하고 SHA-256을 계산한다. 기존 vendor 저장소가 있으면 `git -C ... rev-parse HEAD`로 커밋을 기록하고, 없으면 `sourceCommit`을 `null`로 쓰되 각 파일 hash는 반드시 기록한다.

```python
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).parents[1]
WORKSPACE = ROOT.parents[1]
SOURCE = WORKSPACE / "spikes/deepagents-lucas-llmwiki/results/corpus/current"
TARGET = ROOT / "corpus/source/posthog"
NAMES = ("onboarding.md", "company-security.md", "time-off-current.md")


def prepare() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    documents = {}
    for name in NAMES:
        source = SOURCE / name
        if not source.is_file():
            raise FileNotFoundError(f"missing existing PostHog corpus: {source}")
        target = TARGET / name
        shutil.copyfile(source, target)
        documents[name] = {"sha256": sha256(target.read_bytes()).hexdigest()}
    vendor = WORKSPACE / "spikes/deepagents-lucas-llmwiki/.internal/vendor/posthog.com"
    completed = subprocess.run(
        ["git", "-C", str(vendor), "rev-parse", "HEAD"], capture_output=True, text=True, check=False
    )
    payload = {
        "source": "https://github.com/PostHog/posthog.com",
        "sourceCommit": completed.stdout.strip() if completed.returncode == 0 else None,
        "documents": documents,
    }
    (TARGET / "provenance.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    prepare()
```

- [ ] **Step 4: 변형 생성기 구현**

Create an empty `scripts/__init__.py`. Then create this generator. The fixtures are normalized to printable ASCII so ReportLab's built-in font and every generated format share the same expected characters.

```python
# scripts/generate_variants.py
import io
import json
import re
import textwrap
import unicodedata
from pathlib import Path

import pymupdf
from docx import Document
from docx.shared import Inches
from PIL import Image
from reportlab.pdfgen import canvas

ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "corpus/source/posthog"
EXPECTED = ROOT / "corpus/expected"
GENERATED = ROOT / "corpus/generated"
NAMES = ("onboarding.md", "company-security.md", "time-off-current.md")


def markdown_to_lines(markdown: str) -> list[str]:
    markdown = re.sub(r"\A---\n.*?\n---\n", "", markdown, flags=re.DOTALL)
    markdown = re.sub(r"!\[[^]]*]\([^)]*\)", "", markdown)
    markdown = re.sub(r"\[([^]]+)]\([^)]*\)", r"\1", markdown)
    markdown = re.sub(r"<[^>]+>", "", markdown)
    markdown = re.sub(r"^[#>*+\-]+\s*", "", markdown, flags=re.MULTILINE)
    markdown = markdown.replace("`", "").replace("**", "").replace("__", "")
    markdown = unicodedata.normalize("NFKD", markdown).encode("ascii", "ignore").decode("ascii")
    lines: list[str] = []
    for raw in markdown.splitlines():
        value = " ".join(raw.split())
        if value:
            lines.extend(textwrap.wrap(value, width=80, break_long_words=False) or [value])
    return lines


def write_pdf(lines: list[str], path: Path) -> None:
    pdf = canvas.Canvas(str(path), pagesize=(612, 792), invariant=1)
    y = 744
    pdf.setFont("Helvetica", 10)
    for line in lines:
        if y < 48:
            pdf.showPage()
            pdf.setFont("Helvetica", 10)
            y = 744
        pdf.drawString(48, y, line)
        y -= 14
    pdf.save()


def write_docx(lines: list[str], path: Path) -> None:
    document = Document()
    for line in lines:
        document.add_paragraph(line)
    document.save(path)


def page_png(page: pymupdf.Page, dpi: int, rotate: bool = False) -> bytes:
    data = page.get_pixmap(dpi=dpi, alpha=False).tobytes("png")
    if not rotate:
        return data
    image = Image.open(io.BytesIO(data)).convert("RGB").rotate(90, expand=True, fillcolor="white")
    output = io.BytesIO()
    image.save(output, format="PNG", dpi=(dpi, dpi))
    return output.getvalue()


def write_image_pdf(source_pdf: Path, target: Path, dpi: int, rotate: bool = False) -> None:
    source = pymupdf.open(source_pdf)
    output = pymupdf.open()
    for page in source:
        image = page_png(page, dpi=dpi, rotate=rotate)
        image_doc = pymupdf.open(stream=image)
        rect = image_doc[0].rect
        target_page = output.new_page(width=rect.width, height=rect.height)
        target_page.insert_image(target_page.rect, stream=image)
        image_doc.close()
    output.save(target)
    output.close()
    source.close()


def write_mixed_pdf(source_pdf: Path, target: Path) -> None:
    source = pymupdf.open(source_pdf)
    output = pymupdf.open()
    for index, page in enumerate(source):
        if index % 2 == 0:
            output.insert_pdf(source, from_page=index, to_page=index)
        else:
            target_page = output.new_page(width=page.rect.width, height=page.rect.height)
            target_page.insert_image(target_page.rect, stream=page_png(page, dpi=300))
    output.save(target)
    output.close()
    source.close()


def write_image_docx(source_pdf: Path, target: Path) -> None:
    source = pymupdf.open(source_pdf)
    document = Document()
    for index, page in enumerate(source):
        document.add_picture(io.BytesIO(page_png(page, dpi=300)), width=Inches(7.0))
        if index + 1 < len(source):
            document.add_page_break()
    document.save(target)
    source.close()


def select_key_phrases(lines: list[str]) -> list[str]:
    eligible = [line for line in lines if 25 <= len(line) <= 80]
    if len(eligible) < 5:
        raise ValueError("source does not contain five usable key phrases")
    points = (0, len(eligible) // 4, len(eligible) // 2, 3 * len(eligible) // 4, len(eligible) - 1)
    return [eligible[index] for index in points]


def generate_all(seed: int = 20260726) -> dict:
    EXPECTED.mkdir(parents=True, exist_ok=True)
    GENERATED.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []
    phrases: dict[str, list[str]] = {}
    for name in NAMES:
        lines = markdown_to_lines((SOURCE / name).read_text(encoding="utf-8"))
        stem = Path(name).stem
        expected_path = EXPECTED / f"{stem}.txt"
        expected_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        phrases[name] = select_key_phrases(lines)
        directory = GENERATED / stem
        directory.mkdir(parents=True, exist_ok=True)
        txt, pdf, docx = directory / "plain.txt", directory / "native.pdf", directory / "native.docx"
        txt.write_text("\n".join(lines) + "\n", encoding="utf-8")
        write_pdf(lines, pdf)
        write_docx(lines, docx)
        variants = [("txt", txt, False), ("pdf", pdf, False), ("docx", docx, False)]
        generated = {
            "scan": (directory / "scan.pdf", lambda p: write_image_pdf(pdf, p, 300)),
            "rotated_scan": (directory / "rotated_scan.pdf", lambda p: write_image_pdf(pdf, p, 300, True)),
            "low_res_scan": (directory / "low_res_scan.pdf", lambda p: write_image_pdf(pdf, p, 100)),
            "mixed_pdf": (directory / "mixed_pdf.pdf", lambda p: write_mixed_pdf(pdf, p)),
            "image_docx": (directory / "image_docx.docx", lambda p: write_image_docx(pdf, p)),
        }
        for variant, (path, writer) in generated.items():
            writer(path)
            variants.append((variant, path, True))
        for variant, path, requires_ocr in variants:
            entries.append({
                "source": name,
                "variant": variant,
                "path": str(path.relative_to(GENERATED)),
                "expected": str(expected_path.relative_to(ROOT)),
                "requiresOcr": requires_ocr,
            })
    (EXPECTED / "key_phrases.json").write_text(json.dumps(phrases, indent=2) + "\n", encoding="utf-8")
    manifest = {"seed": seed, "files": entries}
    (GENERATED / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    generate_all()
```

- [ ] **Step 5: 생성 테스트와 실제 스크립트 실행**

Run: `uv run pytest tests/test_corpus_generation.py -v && uv run python -m scripts.prepare_posthog_corpus && uv run python -m scripts.generate_variants`

Expected: `3 passed`; `corpus/generated/manifest.json`과 문서별 8개 변형 생성.

- [ ] **Step 6: 가능한 경우 원본·정답·스크립트만 커밋**

```bash
git add spikes/document-parsing-posthog/scripts spikes/document-parsing-posthog/tests/test_corpus_generation.py spikes/document-parsing-posthog/corpus/source spikes/document-parsing-posthog/corpus/expected
git commit -m "test: add reproducible posthog document corpus"
```

### Task 8: 이미지 기반 DOCX OCR

**Files:**
- Modify: `spikes/document-parsing-posthog/src/document_parser/docx_parser.py`
- Modify: `spikes/document-parsing-posthog/src/document_parser/ocr.py`
- Modify: `spikes/document-parsing-posthog/tests/test_docx_parser.py`

- [ ] **Step 1: 생성된 이미지 DOCX OCR 테스트 추가**

Add `import pytest` and `ROOT = Path(__file__).parents[1]` to `test_docx_parser.py`, then append:

```python
@pytest.mark.ocr
def test_image_only_docx_uses_ocr():
    from document_parser.ocr import tesseract_available
    from scripts.generate_variants import generate_all

    if not tesseract_available("eng"):
        pytest.skip("Tesseract eng traineddata is not installed")
    manifest = generate_all(seed=20260726)
    item = next(entry for entry in manifest["files"] if entry["source"] == "time-off-current.md" and entry["variant"] == "image_docx")
    result = parse(ROOT / "corpus/generated" / item["path"])
    assert result.error is None
    assert result.used_ocr is True
    assert "time off" in result.text.lower()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_docx_parser.py::test_image_only_docx_uses_ocr -v`

Expected: FAIL 또는 SKIP — 이미지 DOCX fallback이 아직 없음.

- [ ] **Step 3: DOCX media OCR 구현**

Add this image-bytes adapter to `ocr.py`:

```python
def ocr_image(image_bytes: bytes, language: str = "eng") -> str:
    if not tesseract_available(language):
        raise DocumentParseFailure("ocr_unavailable", f"Tesseract 언어 데이터가 없습니다: {language}")
    source = pymupdf.open(stream=image_bytes)
    pixmap = source[0].get_pixmap(dpi=300, alpha=False)
    ocr_pdf = pymupdf.open("pdf", pixmap.pdfocr_tobytes(language=language))
    text = ocr_pdf[0].get_text("text", sort=True).strip()
    ocr_pdf.close()
    source.close()
    return text
```

Replace `docx_parser.py` with:

```python
# src/document_parser/docx_parser.py
import re
from pathlib import Path
from zipfile import BadZipFile, ZipFile

from docx import Document
from docx.opc.exceptions import PackageNotFoundError
from docx.table import Table
from docx.text.paragraph import Paragraph

from .errors import DocumentParseFailure
from .models import PageResult, ParseError, ParseResult
from .ocr import ocr_image


def _native_lines(path: Path) -> list[str]:
    document = Document(path)
    lines: list[str] = []
    for block in document.iter_inner_content():
        if isinstance(block, Paragraph):
            value = block.text.strip()
            if value:
                lines.append(value)
        elif isinstance(block, Table):
            for row in block.rows:
                values = [cell.text.strip().replace("\n", " ") for cell in row.cells]
                lines.append(" | ".join(values))
    return lines


def _media_sort_key(name: str) -> tuple[int, str]:
    match = re.search(r"(\d+)(?=\.[^.]+$)", name)
    return (int(match.group(1)) if match else 0, name)


def parse_docx(path: Path, language: str = "eng") -> ParseResult:
    try:
        lines = _native_lines(path)
    except (BadZipFile, PackageNotFoundError, ValueError, KeyError):
        return ParseResult(error=ParseError("corrupt_document", "DOCX 파일을 읽을 수 없습니다."))
    if lines:
        text = "\n".join(lines)
        return ParseResult(text=text, pages=(PageResult(1, text, "native", 1.0),), quality_score=1.0)

    try:
        with ZipFile(path) as archive:
            media = sorted(
                (name for name in archive.namelist() if name.startswith("word/media/")),
                key=_media_sort_key,
            )
            images = [archive.read(name) for name in media]
    except BadZipFile:
        return ParseResult(error=ParseError("corrupt_document", "DOCX 파일을 읽을 수 없습니다."))
    if not images:
        return ParseResult(error=ParseError("native_extraction_empty", "DOCX에서 텍스트를 찾지 못했습니다."))

    pages: list[PageResult] = []
    failed: list[int] = []
    errors: list[str] = []
    for index, image in enumerate(images, start=1):
        try:
            text = ocr_image(image, language=language)
        except DocumentParseFailure as exc:
            failed.append(index)
            errors.append(exc.code)
            continue
        if not text:
            failed.append(index)
            errors.append("low_quality")
            continue
        pages.append(PageResult(index, text, "ocr", 0.95))
    combined = "\n\f\n".join(page.text for page in pages)
    if failed and pages:
        return ParseResult(
            text=combined,
            pages=tuple(pages),
            used_ocr=True,
            quality_score=0.95 * len(pages) / len(images),
            warnings=tuple(dict.fromkeys(errors)),
            error=ParseError("partial_failure", "일부 DOCX 이미지를 추출하지 못했습니다.", tuple(failed)),
        )
    if failed:
        code = errors[0] if errors else "ocr_failed"
        return ParseResult(error=ParseError(code, "DOCX 이미지 OCR에 실패했습니다.", tuple(failed)))
    return ParseResult(text=combined, pages=tuple(pages), used_ocr=True, quality_score=0.95)
```

- [ ] **Step 4: DOCX 전체 테스트 실행**

Run: `uv run pytest tests/test_docx_parser.py -v`

Expected: Tesseract가 있으면 모든 테스트 PASS, 없으면 이미지 DOCX 테스트만 SKIP.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog/src spikes/document-parsing-posthog/tests/test_docx_parser.py
git commit -m "feat: ocr image-only docx documents"
```

### Task 9: 품질 지표와 합격 판정

**Files:**
- Create: `spikes/document-parsing-posthog/src/document_parser/quality.py`
- Create: `spikes/document-parsing-posthog/tests/test_quality.py`

- [ ] **Step 1: CER·핵심 문구·순서 테스트 작성**

```python
# tests/test_quality.py
from document_parser.quality import evaluate_quality


def test_exact_text_has_perfect_quality():
    result = evaluate_quality(
        expected="Time off\nMinimum 25 days\nNo approval required",
        actual="Time off\nMinimum 25 days\nNo approval required",
        key_phrases=("Minimum 25 days", "No approval required"),
    )
    assert result.cer == 0.0
    assert result.key_phrase_recall == 1.0
    assert result.order_preserved is True


def test_missing_and_reordered_phrases_are_detected():
    result = evaluate_quality(
        expected="Alpha Beta Gamma",
        actual="Gamma Alpha",
        key_phrases=("Alpha", "Beta", "Gamma"),
    )
    assert result.cer > 0
    assert result.key_phrase_recall == 2 / 3
    assert result.order_preserved is False
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_quality.py -v`

Expected: FAIL — 품질 평가기가 없음.

- [ ] **Step 3: Levenshtein 기반 최소 구현**

```python
# src/document_parser/quality.py
from dataclasses import dataclass

from rapidfuzz.distance import Levenshtein

from .normalize import normalize_text


@dataclass(frozen=True)
class QualityResult:
    cer: float
    key_phrase_recall: float
    order_preserved: bool


def evaluate_quality(expected: str, actual: str, key_phrases: tuple[str, ...]) -> QualityResult:
    expected_n = normalize_text(expected).casefold()
    actual_n = normalize_text(actual).casefold()
    cer = Levenshtein.distance(expected_n, actual_n) / max(len(expected_n), 1)
    normalized_phrases = tuple(normalize_text(value).casefold() for value in key_phrases)
    found = tuple(value for value in normalized_phrases if value in actual_n)
    recall = len(found) / max(len(normalized_phrases), 1)
    positions = [actual_n.find(value) for value in normalized_phrases]
    present_positions = [position for position in positions if position >= 0]
    order_preserved = len(present_positions) == len(positions) and present_positions == sorted(present_positions)
    return QualityResult(cer=cer, key_phrase_recall=recall, order_preserved=order_preserved)
```

- [ ] **Step 4: 품질 테스트 통과 확인**

Run: `uv run pytest tests/test_quality.py -v`

Expected: `2 passed`.

- [ ] **Step 5: 가능한 경우 커밋**

```bash
git add spikes/document-parsing-posthog/src/document_parser/quality.py spikes/document-parsing-posthog/tests/test_quality.py
git commit -m "test: measure extraction accuracy and ordering"
```

### Task 10: 전체 평가 리포트와 완료 검증

**Files:**
- Create: `spikes/document-parsing-posthog/src/document_parser/report.py`
- Create: `spikes/document-parsing-posthog/scripts/evaluate_corpus.py`
- Create: `spikes/document-parsing-posthog/tests/test_report.py`
- Modify: `spikes/document-parsing-posthog/README.md`
- Create during execution: `spikes/document-parsing-posthog/results/report.md`

- [ ] **Step 1: 리포트 렌더링 테스트 작성**

```python
# tests/test_report.py
from document_parser.report import render_report


def test_report_contains_summary_and_variant_rows():
    report = render_report([
        {
            "source": "time-off-current.md",
            "variant": "pdf",
            "status": "pass",
            "cer": 0.0,
            "keyPhraseRecall": 1.0,
            "usedOcr": False,
            "elapsedMs": 12,
            "error": None,
        }
    ])
    assert "Overall: PASS" in report
    assert "time-off-current.md" in report
    assert "| pdf |" in report
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `uv run pytest tests/test_report.py -v`

Expected: FAIL — 리포트 함수가 없음.

- [ ] **Step 3: Markdown 리포트 구현**

```python
# src/document_parser/report.py
from datetime import datetime, timezone
import platform

import pymupdf

from .ocr import tesseract_available


def render_report(rows: list[dict], generated_at: str | None = None) -> str:
    statuses = {row["status"] for row in rows}
    overall = "FAIL" if "fail" in statuses else "BLOCKED" if "blocked" in statuses else "PASS"
    generated_at = generated_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    lines = [
        "# PostHog Document Parsing Report",
        "",
        f"Overall: {overall}",
        "",
        f"- Generated: {generated_at}",
        f"- Python: {platform.python_version()}",
        f"- PyMuPDF: {pymupdf.__version__}",
        f"- Tesseract eng: {'available' if tesseract_available('eng') else 'unavailable'}",
        "",
        "| source | variant | status | CER | key phrase recall | OCR | elapsed ms | error |",
        "|---|---|---:|---:|---:|---:|---:|---|",
    ]
    for row in sorted(rows, key=lambda value: (value["source"], value["variant"])):
        lines.append(
            f"| {row['source']} | {row['variant']} | {row['status']} | "
            f"{row['cer']:.4f} | {row['keyPhraseRecall']:.2%} | "
            f"{str(row['usedOcr']).lower()} | {row['elapsedMs']} | {row['error'] or ''} |"
        )
    lines.extend([
        "",
        "## Acceptance thresholds",
        "",
        "- TXT, native PDF, native DOCX: key phrase recall 100%",
        "- Clean scan: key phrase recall at least 95%",
        "- Degraded and mixed OCR variants: key phrase recall at least 80%",
        "",
        "## Remaining risk",
        "",
        "PostHog fixtures are English. Korean OCR quality remains unverified until anonymized real documents are added.",
        "",
    ])
    return "\n".join(lines)
```

- [ ] **Step 4: 전체 평가 스크립트 구현**

```python
# scripts/evaluate_corpus.py
import json
from pathlib import Path
from time import perf_counter

from document_parser import parse
from document_parser.ocr import tesseract_available
from document_parser.quality import evaluate_quality
from document_parser.report import render_report
from scripts.generate_variants import generate_all
from scripts.prepare_posthog_corpus import prepare

ROOT = Path(__file__).parents[1]
THRESHOLDS = {"txt": 1.0, "pdf": 1.0, "docx": 1.0, "scan": 0.95}


def evaluate() -> tuple[str, list[dict]]:
    prepare()
    manifest = generate_all(seed=20260726)
    phrases = json.loads((ROOT / "corpus/expected/key_phrases.json").read_text(encoding="utf-8"))
    rows: list[dict] = []
    for item in manifest["files"]:
        path = ROOT / "corpus/generated" / item["path"]
        expected = (ROOT / item["expected"]).read_text(encoding="utf-8")
        started = perf_counter()
        result = parse(path)
        elapsed_ms = round((perf_counter() - started) * 1000)
        quality = evaluate_quality(expected, result.text, tuple(phrases[item["source"]]))
        if item["requiresOcr"] and not tesseract_available("eng"):
            status = "blocked"
            error = "ocr_unavailable"
        elif result.error is not None:
            status = "fail"
            error = result.error.code
        else:
            threshold = THRESHOLDS.get(item["variant"], 0.80)
            status = "pass" if quality.key_phrase_recall >= threshold else "fail"
            error = None if status == "pass" else "quality_below_threshold"
        rows.append({
            "source": item["source"],
            "variant": item["variant"],
            "status": status,
            "cer": quality.cer,
            "keyPhraseRecall": quality.key_phrase_recall,
            "orderPreserved": quality.order_preserved,
            "usedOcr": result.used_ocr,
            "elapsedMs": elapsed_ms,
            "error": error,
        })
    report = render_report(rows)
    artifacts = ROOT / "results/artifacts"
    artifacts.mkdir(parents=True, exist_ok=True)
    (artifacts / "evaluation.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    (ROOT / "results/report.md").write_text(report, encoding="utf-8")
    return report, rows


def main() -> int:
    report, rows = evaluate()
    print(report)
    statuses = {row["status"] for row in rows}
    return 1 if "fail" in statuses else 2 if "blocked" in statuses else 0


if __name__ == "__main__":
    raise SystemExit(main())
```

Append a corpus assertion to `tests/test_corpus_generation.py`:

```python
def test_every_key_phrase_occurs_in_expected_text():
    prepare()
    generate_all(seed=20260726)
    phrases = json.loads((ROOT / "corpus/expected/key_phrases.json").read_text(encoding="utf-8"))
    for source, values in phrases.items():
        expected = (ROOT / "corpus/expected" / f"{Path(source).stem}.txt").read_text(encoding="utf-8")
        assert len(values) == 5
        assert all(value in expected for value in values)
```

- [ ] **Step 5: 리포트 단위 테스트와 비-OCR 전체 테스트 실행**

Run: `uv run pytest -m "not ocr" -v`

Expected: 모든 비-OCR 테스트 PASS, warning 없음.

- [ ] **Step 6: OCR 포함 전체 검증 실행**

Run: `uv run pytest -v && uv run python -m scripts.evaluate_corpus`

Expected with Tesseract: 모든 테스트 PASS, 평가 exit 0, `results/report.md`의 Overall이 PASS.

Expected without Tesseract: OCR 테스트 SKIP, 평가 exit 2, `results/report.md`의 Overall이 BLOCKED이며 원인이 `ocr_unavailable`.

- [ ] **Step 7: README 실행·해석 절차 완성**

README에 다음 명령과 결과 위치를 기록한다.

```bash
uv sync
uv run python -m scripts.prepare_posthog_corpus
uv run python -m scripts.generate_variants
uv run pytest -m "not ocr"
uv run pytest
uv run python -m scripts.evaluate_corpus
```

또한 실제 비식별 문서는 `corpus/private/`에만 두고 결과 리포트에 원문을 쓰지 않는다고 명시한다.

- [ ] **Step 8: placeholder와 추적 파일 점검**

Run: `rg -n 'TB''D|TO''DO|implement ''later|fill ''in' README.md src scripts tests corpus/expected || true`

Expected: 출력 없음.

Run: `find corpus/generated corpus/private results/artifacts -type f | git check-ignore --stdin`

Expected in a Git repository: 생성물·비공개 문서·artifact가 모두 ignore 대상으로 출력됨.

- [ ] **Step 9: 가능한 경우 최종 커밋**

```bash
git add spikes/document-parsing-posthog
git commit -m "feat: complete posthog parsing quality spike"
```

## 완료 후 판단

리포트가 PASS이면 `src/document_parser/`를 실제 백엔드 또는 워커 패키지로 이식하는 별도 설계를 시작한다. BLOCKED이면 OCR 실행 환경만 마련한 뒤 Task 10의 검증을 다시 실행한다. FAIL이면 리포트에서 실패한 형식만 대상으로 파서 또는 변형 생성기를 수정하며, Wiki/LLM 단계로 범위를 넓히지 않는다.
