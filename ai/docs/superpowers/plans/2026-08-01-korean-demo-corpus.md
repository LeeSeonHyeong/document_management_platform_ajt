# 한국어 데모 코퍼스 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한국어 사내 인사·복무 규정 데모 코퍼스를 만들어, RAG 대비 위키 우위(병합·모순 해소·교차참조·멱등)를 의도적으로 보여주고 DOCX·PDF·MD·TXT 형식 경우의 수를 실제 파일로 커버한다.

**Architecture:** 원문(SSOT)은 `experiments/corpus-ko-wiki/sources/*.md`에 저작한다. `build.py`가 이를 DOCX·PDF·MD·TXT로 렌더해 `documents/`에 커밋한다(시연 turnkey). 파이프라인 진입점이 둘이다 — **형식 커버리지**는 `document_parser.parse()`가 `documents/`를 파싱해 검증하고, **위키 생성**은 이미-파싱된 `sources/*.md`를 `experiments/backend_sim.py`에 먹여 검증한다(하네스가 바이너리를 파싱하지 않기 때문, `backend_sim.py:625` `read_text`). 견고성(깨진/부분실패)은 별도 파일로 두고 파서가 오류코드로 떨어지는지 확인한다.

**Tech Stack:** Python 3.12, uv, `python-docx`(DOCX 렌더), `pymupdf`(PDF 렌더·파싱), pytest. 기존 `document_parser` 패키지와 `tests/parsing/`의 생성 기법을 재사용한다.

## Global Constraints

- 위치는 `experiments/corpus-ko-wiki/` 한 곳. `ai/` 밖(frontend·공통 docs·공개 API)은 건드리지 않는다.
- **파일명은 ASCII**(하이픈), **본문·제목은 한국어**. 기존 코퍼스 컨벤션(`01-training.md`, `annual-leave.md`)을 따른다.
- 형식은 **DOCX·PDF·MD·TXT만**. OCR(스캔본 이미지 PDF)은 만들지 않는다.
- SSOT는 `sources/*.md`. `build.py`가 렌더하고 **렌더된 바이너리도 커밋**한다.
- **PDF 한글은 반드시 한국어 폰트를 임베드**해 렌더한다 — PyMuPDF 기본 base-14 폰트는 한글 글리프가 없어 깨진다.
- 모델명은 정확히 쓴다: `claude-sonnet-4-6`(별칭 금지, `ai/CLAUDE.md` 함정).
- stage 는 **고친 경로를 하나하나 명시**한다. `git add -A`/`git add .` 금지. 이 작업은 전부 `ai/` 범위라 백엔드 커밋 분리 이슈는 없다.
- `mcp`라는 이름의 디렉터리/테스트 폴더를 만들지 않는다. `tests/` 하위 새 폴더는 `__init__.py`를 둔다.
- 완료 기준: `uv run pytest -m "not ocr"` 기존 실패 0 (내가 늘린 실패가 없어야 한다).

## 파일 구조

```
experiments/corpus-ko-wiki/
  README.md          # 도메인·문서별「의도→기대 위키 결과→RAG라면 무엇이 무너지나」+ 검증 명령 + 벌크 seam
  ontology.md        # 주제 목록 + 문서×주제 매트릭스 (히어로↔벌크 공유 계약)
  sources/           # SSOT 원문 (사람이 읽고 수정)
    01-service-rules-v1.md            # 복무규정 v1.0 — 연차 15일, 교통비만
    02-hr-committee-minutes-2024-03.md# 인사위원회 회의록 — 15→20일 의결, 식비 신설
    03-service-rules-amendment.md     # 복무규정 개정 공지 — 연차 20일 확정, 반차는 v1.0 참조
    04-business-trip-guide.md         # 출장 관리 지침 — 숙박비 정산
    05-expense-faq.md                 # 출장비 정산 FAQ — "여비" 어휘, 한도·영수증
    06-teamlead-minutes-2024-06.md    # 팀리더 정기회의록 — 재택 주2일, 대체휴일 승인
    07-onboarding-guide.md            # 신입 온보딩 — 연차·재택·보안 요약(교차참조 허브)
    08-infosec-policy.md              # 정보보안 지침 — 독립 주제(대조군)
  build.py           # sources → documents 렌더
  documents/         # 렌더된 배포 파일 (커밋)
    01-service-rules-v1.docx
    02-hr-committee-minutes-2024-03.pdf
    03-service-rules-amendment.md     # md 는 그대로 복사
    04-business-trip-guide.docx
    05-expense-faq.txt
    06-teamlead-minutes-2024-06.pdf
    07-onboarding-guide.docx
    08-infosec-policy.md
    broken/          # 견고성 데모용 (의도적으로 깨진 파일)
      corrupt.docx
      corrupt.pdf
tests/corpus/
  __init__.py
  test_corpus_ko_wiki.py   # 원문 불변식 + 렌더 라운드트립 + 견고성 파일 오류코드
```

형식 배정: DOCX = 01·04·07, PDF = 02·06, MD = 03·08, TXT = 05. 재업로드 멱등은 03을 두 번 먹이는 시나리오(별도 파일 없음, README에 명시).

---

### Task 1: 온톨로지 계약 (`ontology.md`)

주제 목록과 문서×주제 매트릭스를 먼저 고정한다. 이후 원문 저작·README·벌크 확장이 모두 이 매트릭스를 참조한다.

**Files:**
- Create: `experiments/corpus-ko-wiki/ontology.md`
- Test: `tests/corpus/test_corpus_ko_wiki.py` (이 태스크에서 파일 생성 + `__init__.py`)

**Interfaces:**
- Produces: `CORPUS_ROOT = experiments/corpus-ko-wiki` 경로 규약, 8개 문서 슬러그(`01-service-rules-v1` … `08-infosec-policy`), 주제 키(`annual-leave`, `half-day`, `substitute-holiday`, `business-trip-expense`, `remote-work`, `salary`, `onboarding`, `infosec`).

- [ ] **Step 1: 테스트 디렉터리와 실패 테스트 작성**

Create `tests/corpus/__init__.py` (빈 파일).

Create `tests/corpus/test_corpus_ko_wiki.py`:

```python
from pathlib import Path

CORPUS_ROOT = Path(__file__).resolve().parents[2] / "experiments" / "corpus-ko-wiki"

TOPICS = [
    "annual-leave", "half-day", "substitute-holiday", "business-trip-expense",
    "remote-work", "salary", "onboarding", "infosec",
]


def test_ontology_lists_every_topic():
    text = (CORPUS_ROOT / "ontology.md").read_text(encoding="utf-8")
    for topic in TOPICS:
        assert topic in text, f"온톨로지에 주제 {topic} 가 없다"
```

- [ ] **Step 2: 실패 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py::test_ontology_lists_every_topic -v`
Expected: FAIL — `FileNotFoundError` (ontology.md 없음).

- [ ] **Step 3: `ontology.md` 작성**

문서×주제 매트릭스를 표로. 각 셀은 그 문서가 그 주제를 어떻게 다루는지(원값/신값/조각/참조):

```markdown
# 코퍼스 온톨로지 — 문서 × 주제 매트릭스

히어로와 (향후) 벌크가 공유하는 계약이다. 벌크 생성기는 이 매트릭스만 읽고 주변을 채운다.

주제 키: annual-leave(연차) · half-day(반차) · substitute-holiday(대체휴일) ·
business-trip-expense(출장비) · remote-work(재택) · salary(급여) ·
onboarding(온보딩) · infosec(정보보안)

| 문서 \ 주제 | annual-leave | half-day | substitute-holiday | business-trip-expense | remote-work | onboarding | infosec |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 01-service-rules-v1 | **15일(구값)** | 규정 | — | 교통비(조각1) | — | — | — |
| 02-hr-committee-minutes-2024-03 | **15→20일 의결** | — | — | 식비 신설(조각2) | — | — | — |
| 03-service-rules-amendment | **20일(현재값)** | v1.0 참조 | — | — | — | — | — |
| 04-business-trip-guide | — | — | — | 숙박비(조각3) | — | — | — |
| 05-expense-faq | — | — | — | 여비 한도·영수증(조각4) | — | — | — |
| 06-teamlead-minutes-2024-06 | — | — | 승인 절차 | — | 주2일 시범 | — | — |
| 07-onboarding-guide | 요약·참조 | — | — | — | 요약·참조 | 절차 | 요약·참조 |
| 08-infosec-policy | — | — | — | — | — | — | 독립 |

## 심어둔 장면
- **모순 해소**: annual-leave 가 01(15일)·02(20일 의결)·03(20일 확정) 세 곳. 위키는 현재값 20일 한 페이지 + 근거 회의록 링크.
- **병합(merge)**: business-trip-expense 가 01·02·04·05 네 문서에 조각(교통비/식비/숙박비/한도). 위키는 한 페이지로.
- **교차참조**: 07 이 annual-leave·remote-work·infosec 를 요약하며 참조 → 링크 허브.
- **멱등**: 03 을 두 번 업로드해도 중복 페이지 없음.
- **대조군**: 08(infosec) 은 겹침 없는 독립 주제.
```

- [ ] **Step 4: 통과 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py::test_ontology_lists_every_topic -v`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add ai/experiments/corpus-ko-wiki/ontology.md ai/tests/corpus/__init__.py ai/tests/corpus/test_corpus_ko_wiki.py
git commit -m "docs: 한국어 데모 코퍼스 온톨로지 매트릭스 추가"
```

---

### Task 2: 히어로 원문 저작 (`sources/*.md`) + 내용 불변식

8개 한국어 원문을 저작한다. 각 문서에 심은 장치(구값/신값/조각/참조)를 불변식 테스트로 고정해 이후 편집이 시나리오를 깨지 않게 한다. **원문은 원본문서다 — 위키식 `[[링크]]`를 넣지 않는다. 교차참조는 자연어("자세한 내용은 반차 규정을 따른다")로 쓴다. 링크는 위키 에이전트가 만든다.**

**Files:**
- Create: `experiments/corpus-ko-wiki/sources/01-service-rules-v1.md` … `08-infosec-policy.md` (8개)
- Modify: `tests/corpus/test_corpus_ko_wiki.py` (불변식 테스트 추가)

**Interfaces:**
- Consumes: Task 1 의 문서 슬러그·주제 키.
- Produces: `sources/*.md` 8개 — Task 3(렌더)·Task 5(README)·Task 6(위키 검증)의 입력.

- [ ] **Step 1: 실패 테스트 작성**

Append to `tests/corpus/test_corpus_ko_wiki.py`:

```python
SOURCES = CORPUS_ROOT / "sources"

def _src(slug: str) -> str:
    return (SOURCES / f"{slug}.md").read_text(encoding="utf-8")


def test_annual_leave_contradiction_is_planted():
    # 구값은 01, 상향 의결은 02, 현재값은 02·03
    assert "15일" in _src("01-service-rules-v1")
    assert "15일" in _src("02-hr-committee-minutes-2024-03")
    assert "20일" in _src("02-hr-committee-minutes-2024-03")
    assert "20일" in _src("03-service-rules-amendment")


def test_business_trip_expense_is_fragmented_across_four_docs():
    fragments = {
        "01-service-rules-v1": "교통비",
        "02-hr-committee-minutes-2024-03": "식비",
        "04-business-trip-guide": "숙박비",
        "05-expense-faq": "여비",
    }
    for slug, token in fragments.items():
        assert token in _src(slug), f"{slug} 에 출장비 조각 {token} 이 없다"


def test_minutes_documents_read_as_minutes():
    for slug in ("02-hr-committee-minutes-2024-03", "06-teamlead-minutes-2024-06"):
        text = _src(slug)
        assert "안건" in text and "의결" in text, f"{slug} 가 회의록 형식이 아니다"


def test_onboarding_is_cross_reference_hub():
    text = _src("07-onboarding-guide")
    for token in ("연차", "재택", "보안"):
        assert token in text, f"온보딩 허브에 {token} 참조가 없다"


def test_infosec_is_independent_control():
    # 대조군: 출장비·연차 수치가 섞이지 않는다
    text = _src("08-infosec-policy")
    assert "정보보안" in text
    assert "출장비" not in text


def test_teamlead_minutes_plants_remote_and_substitute_holiday():
    text = _src("06-teamlead-minutes-2024-06")
    assert "재택" in text
    assert "대체휴일" in text
```

- [ ] **Step 2: 실패 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py -v`
Expected: FAIL — sources 파일 없음(`FileNotFoundError`).

- [ ] **Step 3: 8개 원문 저작**

각 문서를 아래 골자로 작성한다. 분량은 문서당 1~4KB. 가상 회사 「(주)아지트」. 날짜는 2024년. 자연스러운 한국어 사내 문서 톤.

- `01-service-rules-v1.md` — 「(주)아지트 복무규정 v1.0 (2023.01)」. 마크다운 표로 연차 **15일**, 반차 규정(반일 단위, 사용 방법). 출장은 **교통비만** 실비 지급. 문서 상단에 제·개정 이력 표.
- `02-hr-committee-minutes-2024-03.md` — 「2024년 3월 인사위원회 회의록」. **안건**/토의/**의결** 구조. 의결1: 연차를 **15일에서 20일로 상향**(2024.04.01 시행). 의결2: 출장 **식비**(1일 3만원) 신설. 참석자·일시.
- `03-service-rules-amendment.md` — 「복무규정 개정 공지」. 연차 **20일**로 확정(회의록 근거 명시). 반차는 "종전 v1.0 규정을 그대로 따른다"고 **참조만**. 시행일.
- `04-business-trip-guide.md` — 「출장 관리 지침」. 출장 신청 절차 + **숙박비** 정산(1박 한도). 표로 지역별 숙박 한도. (교통비·식비는 "복무규정 및 인사위원회 의결에 따른다"고 참조.)
- `05-expense-faq.md` — 「출장비 정산 FAQ」. Q&A 형식. "**여비**" 어휘 사용(출장비와 같은 뜻). 영수증 제출 기한, 한도 초과 시 처리, 카드/현금 구분.
- `06-teamlead-minutes-2024-06.md` — 「2024년 6월 팀리더 정기회의록」. **안건**/**의결** 구조. 의결1: **재택**근무 주 2일 시범 도입. 의결2: **대체휴일**은 인사팀 사전 승인 필요.
- `07-onboarding-guide.md` — 「신입사원 온보딩 가이드」. 절차(입사 1일차~4주차). 각 주제를 한 줄 요약하고 원 규정을 가리킴: "**연차**는 복무규정 참조", "**재택**은 팀 정책 참조", "**보안** 서약은 정보보안 지침 참조". DOCX 렌더 시 표 포함(체크리스트).
- `08-infosec-policy.md` — 「정보보안 지침」. 계정·비밀번호·기밀 문서 취급·반출 금지. 다른 주제와 겹치지 않는 독립 문서. "**정보보안**" 포함, "출장비" 미포함.

- [ ] **Step 4: 통과 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py -v`
Expected: PASS (Task 1 + Task 2 테스트 전부).

- [ ] **Step 5: 커밋**

```bash
git add ai/experiments/corpus-ko-wiki/sources ai/tests/corpus/test_corpus_ko_wiki.py
git commit -m "docs: 한국어 데모 코퍼스 히어로 원문 8건 추가"
```

---

### Task 3: 렌더 스크립트 (`build.py`) + 라운드트립 검증

`sources/*.md`를 형식별로 렌더해 `documents/`에 쓴다. **핵심 위험은 PDF 한글 폰트** — 임베드를 안 하면 파싱이 깨진다. 렌더 결과를 `document_parser.parse()`로 되읽어 한글이 살아있는지(그리고 OCR로 안 떨어지는지) 테스트한다.

**Files:**
- Create: `experiments/corpus-ko-wiki/build.py`
- Create(렌더 산출물, 커밋): `experiments/corpus-ko-wiki/documents/*.{docx,pdf,md,txt}`
- Modify: `tests/corpus/test_corpus_ko_wiki.py` (라운드트립 테스트)

**Interfaces:**
- Consumes: `sources/*.md` (Task 2).
- Produces: `documents/` 렌더 파일. `build.py` 의 함수 `build_all(root: Path) -> list[Path]` (생성한 경로 반환), `_korean_font() -> str`, `render_pdf(md_text: str, out: Path) -> None`, `render_docx(md_text: str, out: Path) -> None`.

포맷 배정 맵(빌드가 참조):

```python
FORMAT_MAP = {
    "01-service-rules-v1": "docx",
    "02-hr-committee-minutes-2024-03": "pdf",
    "03-service-rules-amendment": "md",
    "04-business-trip-guide": "docx",
    "05-expense-faq": "txt",
    "06-teamlead-minutes-2024-06": "pdf",
    "07-onboarding-guide": "docx",
    "08-infosec-policy": "md",
}
```

- [ ] **Step 1: 라운드트립 실패 테스트 작성**

Append to `tests/corpus/test_corpus_ko_wiki.py`:

```python
import pytest
from document_parser import parse
from document_parser.pdf_parser import needs_ocr

DOCUMENTS = CORPUS_ROOT / "documents"

RENDERED = {
    "01-service-rules-v1.docx": "연차",
    "02-hr-committee-minutes-2024-03.pdf": "의결",
    "03-service-rules-amendment.md": "20일",
    "04-business-trip-guide.docx": "숙박비",
    "05-expense-faq.txt": "여비",
    "06-teamlead-minutes-2024-06.pdf": "재택",
    "07-onboarding-guide.docx": "온보딩",
    "08-infosec-policy.md": "정보보안",
}


@pytest.mark.parametrize("filename,token", RENDERED.items())
def test_rendered_document_parses_and_keeps_korean(filename: str, token: str):
    result = parse(DOCUMENTS / filename)
    assert result.error is None, f"{filename} 파싱 실패: {result.error}"
    assert token in result.text, f"{filename} 파싱 결과에 '{token}' 없음(폰트/렌더 깨짐 의심)"


@pytest.mark.parametrize("filename", ["02-hr-committee-minutes-2024-03.pdf",
                                      "06-teamlead-minutes-2024-06.pdf"])
def test_rendered_pdf_does_not_fall_back_to_ocr(filename: str):
    result = parse(DOCUMENTS / filename)
    assert result.used_ocr is False, f"{filename} 가 OCR 로 떨어짐 — 한글 폰트 임베드 실패"
    assert needs_ocr(result.text) is False
```

- [ ] **Step 2: 실패 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py -k "rendered" -v`
Expected: FAIL — documents 파일 없음.

- [ ] **Step 3: `build.py` 작성**

```python
"""sources/*.md → documents/ 렌더. 원문(SSOT)에서 배포 형식을 재현한다.

PDF 는 한글 글리프가 있는 시스템 폰트를 임베드한다 — PyMuPDF 기본 base-14 폰트에는
한글이 없어 임베드를 빼면 파싱이 깨진다(빈 텍스트 → OCR 로 오인).
CI(리눅스)에서 도는 게 아니라 로컬 1회 생성용이라 macOS 시스템 폰트를 먼저 찾는다.
렌더 산출물은 커밋하므로 시연에서 이 스크립트를 다시 돌릴 필요는 없다.
"""
from pathlib import Path

import pymupdf
from docx import Document
from docx.enum.style import WD_STYLE_TYPE

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources"
DOCUMENTS = ROOT / "documents"

FORMAT_MAP = {
    "01-service-rules-v1": "docx",
    "02-hr-committee-minutes-2024-03": "pdf",
    "03-service-rules-amendment": "md",
    "04-business-trip-guide": "docx",
    "05-expense-faq": "txt",
    "06-teamlead-minutes-2024-06": "pdf",
    "07-onboarding-guide": "docx",
    "08-infosec-policy": "md",
}

KOREAN_FONT_CANDIDATES = [
    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
    "/Library/Fonts/NanumGothic.ttf",
]


def _korean_font() -> str:
    for candidate in KOREAN_FONT_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    raise SystemExit(
        "한국어 PDF 렌더용 폰트를 찾지 못했습니다. NanumGothic 등을 설치하거나 "
        "build.py 의 KOREAN_FONT_CANDIDATES 에 경로를 추가하세요."
    )


def render_pdf(md_text: str, out: Path) -> None:
    font = _korean_font()
    doc = pymupdf.open()
    lines = md_text.splitlines() or [""]
    per_page = 42
    for start in range(0, len(lines), per_page):
        page = doc.new_page()
        rect = pymupdf.Rect(60, 60, page.rect.width - 60, page.rect.height - 60)
        chunk = "\n".join(lines[start:start + per_page])
        leftover = page.insert_textbox(
            rect, chunk, fontname="ko", fontfile=font, fontsize=11
        )
        if leftover < 0:
            # per_page 가 커서 안 들어감 — 줄 수를 줄여 다시 시도
            raise SystemExit(f"{out.name}: 페이지에 텍스트가 넘침, per_page 조정 필요")
    doc.save(out)
    doc.close()


def render_docx(md_text: str, out: Path) -> None:
    doc = Document()
    doc.styles.add_style("제목 2", WD_STYLE_TYPE.PARAGRAPH)
    for raw in md_text.splitlines():
        line = raw.rstrip()
        if not line:
            continue
        if line.startswith("| ") and line.endswith(" |"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if set("".join(cells)) <= set("-: "):   # 표 구분선(|---|) 은 건너뛴다
                continue
            table = doc.add_table(rows=1, cols=len(cells))
            for i, cell in enumerate(cells):
                table.rows[0].cells[i].text = cell
        elif line.startswith("#"):
            level = min(len(line) - len(line.lstrip("#")), 6)
            doc.add_heading(line.lstrip("# ").strip(), level=level)
        else:
            doc.add_paragraph(line)
    doc.save(out)


def build_all(root: Path = ROOT) -> list[Path]:
    (root / "documents").mkdir(exist_ok=True)
    written: list[Path] = []
    for slug, fmt in FORMAT_MAP.items():
        md_text = (root / "sources" / f"{slug}.md").read_text(encoding="utf-8")
        out = root / "documents" / f"{slug}.{fmt}"
        if fmt == "md":
            out.write_text(md_text, encoding="utf-8")
        elif fmt == "txt":
            out.write_text(md_text, encoding="utf-8")
        elif fmt == "docx":
            render_docx(md_text, out)
        elif fmt == "pdf":
            render_pdf(md_text, out)
        written.append(out)
    return written


if __name__ == "__main__":
    for path in build_all():
        print(f"wrote {path.relative_to(ROOT.parent.parent)}")
```

> 참고: DOCX 표는 연속된 `| a | b |` 마크다운 행을 각각 1행 표로 렌더한다(파서가 `a | b`로 되읽음, `tests/parsing/test_docx_parser.py` 계약과 일치). 인접 행 병합까지는 필요 없다 — 파싱 라운드트립만 보장하면 된다.

- [ ] **Step 4: 렌더 실행**

Run: `cd ai && uv run python experiments/corpus-ko-wiki/build.py`
Expected: `documents/` 에 8개 파일 생성(01·04·07 docx, 02·06 pdf, 03·08 md, 05 txt). 폰트 못 찾으면 SystemExit 메시지 — 그 경로에 한국어 폰트를 설치/추가하고 재실행.

- [ ] **Step 5: 라운드트립 통과 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py -k "rendered" -v`
Expected: PASS (8개 파싱 + 2개 PDF non-OCR).

- [ ] **Step 6: 커밋 (스크립트 + 렌더 산출물)**

```bash
git add ai/experiments/corpus-ko-wiki/build.py ai/experiments/corpus-ko-wiki/documents ai/tests/corpus/test_corpus_ko_wiki.py
git commit -m "feat: 한국어 데모 코퍼스 렌더 스크립트와 배포 파일 추가"
```

---

### Task 4: 견고성 데모 파일 (`documents/broken/`) + 오류코드 검증

시연에서 "깨진 파일을 올려도 파서가 안 죽고 오류로 떨어진다"를 보여줄 실제 파일을 만든다. 단위 테스트(`tests/parsing/`)는 이미 tmp_path 로 이 경로를 덮지만, **업로드할 실물**은 없으므로 커밋한다.

**Files:**
- Create: `experiments/corpus-ko-wiki/documents/broken/corrupt.docx`, `corrupt.pdf`
- Modify: `tests/corpus/test_corpus_ko_wiki.py` (견고성 파일 오류코드 테스트)

**Interfaces:**
- Consumes: `document_parser.parse()` (기존).
- Produces: `documents/broken/` 데모 파일.

- [ ] **Step 1: 실패 테스트 작성**

Append to `tests/corpus/test_corpus_ko_wiki.py`:

```python
BROKEN = CORPUS_ROOT / "documents" / "broken"


def test_broken_files_fail_with_clean_error_codes():
    docx_result = parse(BROKEN / "corrupt.docx")
    assert docx_result.error is not None
    assert docx_result.error.code == "corrupt_document"

    pdf_result = parse(BROKEN / "corrupt.pdf")
    assert pdf_result.error is not None
    assert pdf_result.error.code == "corrupt_document"
```

- [ ] **Step 2: 실패 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py::test_broken_files_fail_with_clean_error_codes -v`
Expected: FAIL — broken 파일 없음.

- [ ] **Step 3: 깨진 파일 생성**

Run:
```bash
cd ai && mkdir -p experiments/corpus-ko-wiki/documents/broken \
  && printf 'not a zip' > experiments/corpus-ko-wiki/documents/broken/corrupt.docx \
  && printf 'not a pdf' > experiments/corpus-ko-wiki/documents/broken/corrupt.pdf
```

(각각 `corrupt_document` 로 떨어진다 — `tests/parsing/test_docx_parser.py:90`·`test_pdf_parser.py:62` 와 같은 원리.)

- [ ] **Step 4: 통과 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py::test_broken_files_fail_with_clean_error_codes -v`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add ai/experiments/corpus-ko-wiki/documents/broken ai/tests/corpus/test_corpus_ko_wiki.py
git commit -m "test: 한국어 데모 코퍼스 견고성 데모 파일 추가"
```

---

### Task 5: README 의도 매핑 + 드리프트 가드

README 에 문서별「의도 → 기대 위키 결과 → RAG라면 무엇이 무너지나」표와 검증 명령을 적는다. 이것이 시연 진행자의 대본이자 회귀 기준이다. 원문·주제가 README 에서 빠지면 실패하는 가벼운 가드 테스트를 둔다.

**Files:**
- Create: `experiments/corpus-ko-wiki/README.md`
- Modify: `tests/corpus/test_corpus_ko_wiki.py` (드리프트 가드)

**Interfaces:**
- Consumes: 모든 슬러그·주제 키.

- [ ] **Step 1: 실패 테스트 작성**

Append to `tests/corpus/test_corpus_ko_wiki.py`:

```python
def test_readme_covers_every_source_and_topic():
    readme = (CORPUS_ROOT / "README.md").read_text(encoding="utf-8")
    for slug in FORMAT_MAP_SLUGS:
        assert slug in readme, f"README 에 {slug} 설명이 없다"
    for topic in TOPICS:
        assert topic in readme, f"README 에 주제 {topic} 가 없다"
```

`tests/corpus/test_corpus_ko_wiki.py` 상단에 슬러그 목록 상수를 추가:

```python
FORMAT_MAP_SLUGS = [
    "01-service-rules-v1", "02-hr-committee-minutes-2024-03",
    "03-service-rules-amendment", "04-business-trip-guide",
    "05-expense-faq", "06-teamlead-minutes-2024-06",
    "07-onboarding-guide", "08-infosec-policy",
]
```

- [ ] **Step 2: 실패 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py::test_readme_covers_every_source_and_topic -v`
Expected: FAIL — README 없음.

- [ ] **Step 3: `README.md` 작성**

포함할 것:
1. 목적 한 단락(한국어 사내 규정, RAG 대비 위키 우위 시연 + 형식 커버리지).
2. **의도 매핑 표** — 열: 문서 슬러그 · 형식 · 심은 장치 · 기대 위키 결과 · RAG라면 무엇이 무너지나. 8행 전부. 예시 행:
   - `01·02·03` 그룹 → "위키: 연차 페이지 1개, 현재값 20일, 근거 회의록 링크 / RAG: 15일·20일 청크를 다 반환해 현재값 불명".
   - `01·02·04·05` 그룹 → "위키: 출장비 페이지 1개(교통비+식비+숙박비+한도) / RAG: 네 문서 조각을 흩어진 채 반환".
3. 주제 키 목록(TOPICS 8개 그대로).
4. **검증 명령 두 개**:
   - 형식 커버리지: `uv run pytest tests/corpus/test_corpus_ko_wiki.py -v`
   - 위키 생성(로컬, 키 불필요): `uv run python experiments/backend_sim.py --runtime claude-code --model claude-sonnet-4-6 --root <임시경로> --report <임시경로>/report.json experiments/corpus-ko-wiki/sources/01-service-rules-v1.md experiments/corpus-ko-wiki/sources/02-hr-committee-minutes-2024-03.md experiments/corpus-ko-wiki/sources/03-service-rules-amendment.md experiments/corpus-ko-wiki/sources/04-business-trip-guide.md experiments/corpus-ko-wiki/sources/05-expense-faq.md`
5. **멱등 시나리오**: 위 명령에 03 을 한 번 더 인자로 주면 중복 페이지가 안 생기는지 확인.
6. **파이프라인 진입점 주의**: `backend_sim.py` 는 원문을 `read_text` 로 읽으므로 `sources/*.md` 를 준다. `documents/` 의 DOCX·PDF 는 실서버(파싱 경유) 업로드 또는 `document_parser.parse()` 로 검증한다.
7. **벌크 확장 seam**: `ontology.md` 매트릭스를 공유하며, 향후 벌크 생성기가 주제 주변을 채운다(이번 범위 밖).

- [ ] **Step 4: 통과 확인**

Run: `cd ai && uv run pytest tests/corpus/test_corpus_ko_wiki.py -v`
Expected: PASS (전체).

- [ ] **Step 5: 커밋**

```bash
git add ai/experiments/corpus-ko-wiki/README.md ai/tests/corpus/test_corpus_ko_wiki.py
git commit -m "docs: 한국어 데모 코퍼스 README 의도 매핑 추가"
```

---

### Task 6: 파이프라인 수동 확인 + 전체 테스트 + PR

코퍼스가 실제로 위키를 만들 때 병합·모순 해소·교차링크가 발화하는지 눈으로 확인하고(LLM 출력이 비결정적이라 자동 단언 불가), 회귀를 확인한 뒤 PR 을 연다.

**Files:**
- Modify: `experiments/corpus-ko-wiki/README.md` (수동 확인 결과 한 단락 추가)

- [ ] **Step 1: 전체 테스트 회귀 확인**

Run: `cd ai && uv run pytest -m "not ocr"`
Expected: 기존 실패 0, 새 `tests/corpus/` 통과. (실패가 늘면 원인 해결 후 진행.)

- [ ] **Step 2: 위키 생성 수동 확인**

Run (임시 루트에 위키 생성):
```bash
cd ai && uv run python experiments/backend_sim.py \
  --runtime claude-code --model claude-sonnet-4-6 \
  --root /private/tmp/ko-wiki-check --report /private/tmp/ko-wiki-check/report.json \
  experiments/corpus-ko-wiki/sources/01-service-rules-v1.md \
  experiments/corpus-ko-wiki/sources/02-hr-committee-minutes-2024-03.md \
  experiments/corpus-ko-wiki/sources/03-service-rules-amendment.md \
  experiments/corpus-ko-wiki/sources/04-business-trip-guide.md \
  experiments/corpus-ko-wiki/sources/05-expense-faq.md
```

확인 항목(생성된 `/private/tmp/ko-wiki-check` 하위 위키 본문·`report.json`):
- 연차 관련 페이지가 **하나**이고 현재값이 **20일**이며 회의록 근거가 링크로 걸렸는가.
- 출장비 페이지가 **하나**로 교통비·식비·숙박비·한도가 모였는가(merge).
- 페이지 간 교차 링크가 생겼는가.

- [ ] **Step 3: 확인 결과 기록**

README 하단에 "수동 확인 (YYYY-MM-DD, claude-sonnet-4-6)" 한 단락으로 위 세 항목의 실제 결과를 적는다(무엇이 잘 되고 무엇이 약했는지). 근거 없는 "동작 확인" 대신 실제 관찰을 적는다(`ai/CLAUDE.md`: 동작 확인은 근거를 구분해 말한다).

```bash
git add ai/experiments/corpus-ko-wiki/README.md
git commit -m "docs: 한국어 데모 코퍼스 위키 생성 수동 확인 결과 기록"
```

- [ ] **Step 4: 푸시 + PR**

```bash
git push -u origin feature/S15P11B106-177-ko-demo-corpus
```

PR 제목: `docs: 한국어 데모 코퍼스 추가 [S15P11B106-177]`
PR 본문(한국어, 컨벤션 템플릿): 관련 이슈 S15P11B106-177, 작업 내용(히어로 8건·형식 커버리지·견고성 파일·의도 매핑), 테스트(형식 라운드트립 자동 + 위키 생성 수동 확인 결과), 스크린샷 없음. Jira 상태를 "진행 중"→"완료(리뷰 대기)"로 갱신.

---

## Self-Review

**1. Spec coverage:**
- 위치·SSOT·렌더 커밋 → Task 3, 파일 구조. ✅
- 히어로 8~9개 · 모순/병합/교차참조/멱등/회의록 → Task 2 원문 + Task 1 매트릭스 + Task 6 확인. ✅
- 형식 DOCX·PDF·MD·TXT, OCR 제외 → Task 3 FORMAT_MAP + 라운드트립. ✅
- 견고성 corrupt/partial_failure → Task 4(실물) + 기존 `tests/parsing/`(단위, partial_failure 포함). ✅ (partial_failure 는 기존 단위 테스트가 이미 커버 — 스펙 §5 의도와 일치)
- README 의도 매핑 → Task 5. ✅
- 벌크 seam → Task 1 ontology + Task 5 README. ✅
- `pytest -m "not ocr"` 실패 0 → Task 6 Step 1. ✅

**2. Placeholder scan:** 각 코드 스텝에 실제 코드 있음. 원문 저작(Task 2 Step 3)은 골자를 문서별로 구체 명시 + 불변식 테스트가 강제. 플레이스홀더 없음.

**3. Type consistency:** `build_all(root)`·`render_pdf`·`render_docx`·`_korean_font`·`FORMAT_MAP`·`FORMAT_MAP_SLUGS`·`TOPICS`·`CORPUS_ROOT` 이름이 태스크 간 일치. 슬러그 8개가 파일 구조·`FORMAT_MAP`·`RENDERED`·`FORMAT_MAP_SLUGS` 에서 동일.
