# corpus-ko-wiki realistic 세트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 corpus-ko-wiki 8문서의 장치를 그대로 유지하면서, 실제 사내 문서처럼 두껍고 잡음이 섞인 realistic 병렬 세트를 추가한다.

**Architecture:** 같은 코퍼스 디렉터리 안에 `sources-realistic/`(SSOT md 8건) → `build.py --realistic` → `documents-realistic/`(렌더 바이너리) 병렬 트리를 만든다. 슬러그·형식·온톨로지 매트릭스는 기존과 공유한다. 검증은 (1) 원문 장치 문자열 회귀, (2) 렌더 산출물 파싱 회귀 두 층으로 고정한다.

**Tech Stack:** Python 3.12, uv, pytest, python-docx, PyMuPDF(pymupdf), `document_parser`(사내).

## Global Constraints

- 기존 `experiments/corpus-ko-wiki/sources/`·`documents/`·`documents/broken/` 는 **수정·삭제하지 않는다.**
- 슬러그 8개와 형식은 기존 `FORMAT_MAP` 그대로: `01·04·07`=docx, `02·06`=pdf, `05`=txt, `03·08`=md.
- **장치 판별 문자열을 verbatim 으로 유지한다.** realistic 원문/렌더에 아래가 반드시 존재해야 한다(회귀가 이를 단언):
  - `01`: `15일`, `교통비`, `연차`, `반차`
  - `02`: `15일`, `20일`, `식비`, `안건`, `의결`
  - `03`: `20일`, `v1.0`
  - `04`: `숙박비`
  - `05`: `여비`
  - `06`: `재택`, `대체휴일`, `안건`, `의결`
  - `07`: `연차`, `재택`, `보안`, `온보딩`
  - `08`: `정보보안` — 그리고 **`출장비` 문자열이 없어야 한다**(대조군).
- PDF(`02·06`)는 `build.py` 의 `KOREAN_FONT_CANDIDATES` 폰트를 임베드해 렌더하고, 파싱 시 **OCR 로 떨어지지 않아야 한다**(`used_ocr is False`).
- `ontology.md` 는 단일 정본. realistic 세트가 이 매트릭스를 공유한다는 문장만 추가한다(매트릭스 복제 금지).
- 견고성(corrupt) 픽스처는 기존 `documents/broken/` 재사용 — realistic 용으로 새로 만들지 않는다.
- 커밋 stage 는 경로를 하나하나 명시한다(`git add -A` 금지). 커밋 메시지는 한국어, Jira 키는 넣지 않는다.
- 작업 위치는 워크트리 `.worktrees/S15P11B106-177-corpus-realistic/ai`. 명령은 `ai/` 기준.

---

## File Structure

| 경로 | 책임 | 신규/수정 |
| --- | --- | --- |
| `experiments/corpus-ko-wiki/sources-realistic/*.md` (8건) | realistic 원문 SSOT. 장치 verbatim + 유형별 골격 + 명명된 잡음 절 | 신규 |
| `experiments/corpus-ko-wiki/documents-realistic/*` (8건) | `build.py --realistic` 렌더 산출(docx/pdf/txt/md). 커밋 | 신규(생성물) |
| `experiments/corpus-ko-wiki/build.py` | `--realistic` 플래그로 realistic 트리를 같은 `FORMAT_MAP` 으로 렌더 | 수정 |
| `experiments/corpus-ko-wiki/ontology.md` | 두 세트가 매트릭스 공유한다는 문장 1개 추가 | 수정 |
| `experiments/corpus-ko-wiki/README.md` | realistic 절 + 위키 생성 수동 확인 절차 추가 | 수정 |
| `tests/corpus/test_corpus_ko_wiki_realistic.py` | realistic 원문 장치·렌더 파싱·문서 일관성 회귀 | 신규 |

## 유형별 원문 저작 스펙 (Task 1 에서 씀)

각 문서는 **기존 `sources/<slug>.md` 의 장치 보유 조항을 verbatim 으로 옮겨** 담고, 아래 골격과 명명된 잡음 절을 더해 실제 문서처럼 만든다. 잡음 절은 위키가 무시해야 할 무관 내용이다(회귀가 잡음 절 표식의 존재를 단언한다).

| slug | 형식 | 골격(섹션) | 명명된 잡음 절(표식 문자열) | 원문 최소 길이 |
| --- | --- | --- | --- | --- |
| 01-service-rules-v1 | 규정 | 제·개정 이력 표 / 총칙(목적·적용범위·용어정의) / 근태 / 휴가(연차 제5조 **15일**, 반차 표) / **복장** / 출장(**교통비** 실비) / **징계** / 부칙 / 서명란 | `복장`, `징계` | 2500 |
| 02-hr-committee-minutes-2024-03 | 회의록 | 회의 개요(일시·장소·참석) / 안건1 **조직개편** / 안건2 연차 **15일→20일** 상향(심의·**의결**) / 안건3 출장 **식비** 신설(의결) / 안건4 **예산** / 안건5 **경조사** / 의결사항 요약 / 서명 | `조직개편`, `예산`, `경조사` | 2500 |
| 03-service-rules-amendment | 개정문(md) | 개정 개요 / **개정 사유** / 신구조문 대비표(구 15일 → 신 **20일**) / **v1.0** 참조 / **경과규정** / 부칙 | `개정 사유`, `경과규정` | 1500 |
| 04-business-trip-guide | 가이드 | 출장 절차 개요 / 사전 승인 / 교통 / **숙박비**(한도표) / 정산 절차 / **자주 하는 실수** / **신청 화면** 안내 | `자주 하는 실수`, `화면` | 1800 |
| 05-expense-faq | FAQ(txt) | Q&A 12건 — 그 중 하나가 **여비** 한도·영수증. 나머지는 **법인카드·주차·통신비** | `법인카드`, `주차`, `통신비` | 1800 |
| 06-teamlead-minutes-2024-06 | 회의록 | 개요 / 안건 **채용** / 안건 **OKR** / 안건 **대체휴일** 승인 절차(**의결**) / 안건 **재택** 주2일 시범(의결) / 안건 **회식** / 의결 요약 / 서명 | `채용`, `OKR`, `회식` | 2500 |
| 07-onboarding-guide | 가이드/허브 | 환영 / 입사 첫날 / **연차** 안내(참조) / **재택** 안내(참조) / 정보**보안** 서약(참조) / **사무용품·좌석·주차** 안내 / 체크리스트. 문서에 `온보딩` 포함 | `사무용품`, `좌석` | 1800 |
| 08-infosec-policy | 정책/대조군 | 목적 / 적용범위 / 계정·**비밀번호** / 접근통제 / 정보자산 **반출입** / 사고 대응 / 부칙. `정보보안` 포함, **`출장비` 금지** | `비밀번호`, `반출입` | 2500 |

**PDF 렌더 함정(02·06):** `build.py` 의 `render_pdf` 는 페이지당 42줄을 `insert_textbox` 로 넣고, 넘치면(`leftover < 0`) `SystemExit` 한다. realistic 원문은 길어서 넘칠 수 있다 → 원문 줄을 한 줄 ~60자 이내로 끊어 쓴다. 그래도 넘치면 `build.py` 의 `per_page` 를 36 으로 낮춘다(이 값은 Task 2 에서 조정 가능).

---

## Task 1: realistic 원문 8건 + 원문 장치 회귀

**Files:**
- Create: `experiments/corpus-ko-wiki/sources-realistic/01-service-rules-v1.md` … `08-infosec-policy.md` (8건, 위 저작 스펙표)
- Test: `tests/corpus/test_corpus_ko_wiki_realistic.py`

**Interfaces:**
- Consumes: 없음.
- Produces: `experiments/corpus-ko-wiki/sources-realistic/<slug>.md` 8건. 각 파일은 Global Constraints 의 장치 문자열을 포함하고, 저작 스펙표의 잡음 표식과 최소 길이를 만족한다. 테스트 상수 `REALISTIC_SLUGS`, `DEVICE_TOKENS`, `NOISE_MARKERS`, `MIN_CHARS` 를 정의한다(Task 2 가 `REALISTIC_SLUGS` 를 재사용).

- [ ] **Step 1: 원문 장치 회귀 테스트를 먼저 쓴다 (실패 예정)**

`tests/corpus/test_corpus_ko_wiki_realistic.py` 생성:

```python
from pathlib import Path

CORPUS_ROOT = Path(__file__).resolve().parents[2] / "experiments" / "corpus-ko-wiki"
SOURCES_R = CORPUS_ROOT / "sources-realistic"

REALISTIC_SLUGS = [
    "01-service-rules-v1", "02-hr-committee-minutes-2024-03",
    "03-service-rules-amendment", "04-business-trip-guide",
    "05-expense-faq", "06-teamlead-minutes-2024-06",
    "07-onboarding-guide", "08-infosec-policy",
]

# 장치 판별 문자열 — realistic 원문에 verbatim 으로 남아야 한다
DEVICE_TOKENS = {
    "01-service-rules-v1": ["15일", "교통비", "연차", "반차"],
    "02-hr-committee-minutes-2024-03": ["15일", "20일", "식비", "안건", "의결"],
    "03-service-rules-amendment": ["20일", "v1.0"],
    "04-business-trip-guide": ["숙박비"],
    "05-expense-faq": ["여비"],
    "06-teamlead-minutes-2024-06": ["재택", "대체휴일", "안건", "의결"],
    "07-onboarding-guide": ["연차", "재택", "보안", "온보딩"],
    "08-infosec-policy": ["정보보안"],
}

# 위키가 무시해야 할 잡음 절 표식 — 존재해야 realistic(잡음 섞임)이 검증된다
NOISE_MARKERS = {
    "01-service-rules-v1": ["복장", "징계"],
    "02-hr-committee-minutes-2024-03": ["조직개편", "예산", "경조사"],
    "03-service-rules-amendment": ["개정 사유", "경과규정"],
    "04-business-trip-guide": ["자주 하는 실수", "화면"],
    "05-expense-faq": ["법인카드", "주차", "통신비"],
    "06-teamlead-minutes-2024-06": ["채용", "OKR", "회식"],
    "07-onboarding-guide": ["사무용품", "좌석"],
    "08-infosec-policy": ["비밀번호", "반출입"],
}

MIN_CHARS = {
    "01-service-rules-v1": 2500, "02-hr-committee-minutes-2024-03": 2500,
    "03-service-rules-amendment": 1500, "04-business-trip-guide": 1800,
    "05-expense-faq": 1800, "06-teamlead-minutes-2024-06": 2500,
    "07-onboarding-guide": 1800, "08-infosec-policy": 2500,
}


def _rsrc(slug: str) -> str:
    return (SOURCES_R / f"{slug}.md").read_text(encoding="utf-8")


import pytest


@pytest.mark.parametrize("slug", REALISTIC_SLUGS)
def test_realistic_source_exists_and_is_substantial(slug):
    text = _rsrc(slug)
    assert len(text) >= MIN_CHARS[slug], f"{slug} realistic 원문이 너무 짧다({len(text)}자)"


@pytest.mark.parametrize("slug", REALISTIC_SLUGS)
def test_realistic_source_keeps_device_tokens(slug):
    text = _rsrc(slug)
    for token in DEVICE_TOKENS[slug]:
        assert token in text, f"{slug} 에 장치 문자열 '{token}' 이 사라졌다"


@pytest.mark.parametrize("slug", REALISTIC_SLUGS)
def test_realistic_source_has_noise_markers(slug):
    text = _rsrc(slug)
    for marker in NOISE_MARKERS[slug]:
        assert marker in text, f"{slug} 에 잡음 절 '{marker}' 이 없다"


def test_infosec_realistic_stays_independent_control():
    text = _rsrc("08-infosec-policy")
    assert "출장비" not in text, "대조군 08 에 출장비가 새어 들어갔다"
```

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/corpus/test_corpus_ko_wiki_realistic.py -q`
Expected: FAIL — `FileNotFoundError`(sources-realistic 없음).

- [ ] **Step 3: realistic 원문 8건을 저작한다**

위 "유형별 원문 저작 스펙" 표대로 `experiments/corpus-ko-wiki/sources-realistic/<slug>.md` 8건을 작성한다. 규칙:
- 기존 `experiments/corpus-ko-wiki/sources/<slug>.md` 를 열어 장치 보유 조항(연차 15일/20일, 교통비·식비·숙박비·여비, 대체휴일·재택, v1.0 참조 등)을 **문구 그대로** 가져온다.
- 그 위에 골격 섹션과 명명된 잡음 절을 채워 실제 문서처럼 만든다. 잡음 절은 해당 주제와 무관한 실제 규정/안건처럼 쓴다.
- `08` 에는 `출장비` 문자열을 절대 넣지 않는다. `07` 의 연차·재택·보안 언급은 잡음이 아니라 의도된 교차참조 허브다.
- PDF 대상(`02·06`)은 한 줄 ~60자 이내로 끊어 쓴다(렌더 오버플로 방지).

- [ ] **Step 4: 통과 확인**

Run: `uv run pytest tests/corpus/test_corpus_ko_wiki_realistic.py -q`
Expected: PASS (25건: 8슬러그 × 3 파라미터 + 대조군 1).

- [ ] **Step 5: 커밋**

```bash
git add tests/corpus/test_corpus_ko_wiki_realistic.py \
        experiments/corpus-ko-wiki/sources-realistic
git commit -m "test: corpus-ko-wiki realistic 원문 8건과 장치 회귀 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: build.py `--realistic` 렌더 + 렌더 파싱 회귀

**Files:**
- Modify: `experiments/corpus-ko-wiki/build.py`
- Create(생성물): `experiments/corpus-ko-wiki/documents-realistic/*` (8건)
- Test: `tests/corpus/test_corpus_ko_wiki_realistic.py` (파싱 회귀 추가)

**Interfaces:**
- Consumes: `sources-realistic/<slug>.md` (Task 1), `REALISTIC_SLUGS` (Task 1 테스트 상수).
- Produces: `build_all(root=ROOT, *, realistic: bool = False) -> list[Path]`. `realistic=True` 면 `sources-realistic/ → documents-realistic/` 를 같은 `FORMAT_MAP` 으로 렌더한다. `documents-realistic/<slug>.<fmt>` 8건.

- [ ] **Step 1: 렌더 파싱 회귀 테스트를 먼저 추가한다 (실패 예정)**

`tests/corpus/test_corpus_ko_wiki_realistic.py` 하단에 추가:

```python
from document_parser import parse
from document_parser.pdf_parser import needs_ocr

DOCUMENTS_R = CORPUS_ROOT / "documents-realistic"

# 렌더 산출물 파일명 → 파싱 결과에 남아야 할 대표 토큰
RENDERED_R = {
    "01-service-rules-v1.docx": "연차",
    "02-hr-committee-minutes-2024-03.pdf": "의결",
    "03-service-rules-amendment.md": "20일",
    "04-business-trip-guide.docx": "숙박비",
    "05-expense-faq.txt": "여비",
    "06-teamlead-minutes-2024-06.pdf": "재택",
    "07-onboarding-guide.docx": "온보딩",
    "08-infosec-policy.md": "정보보안",
}


@pytest.mark.parametrize("filename,token", RENDERED_R.items())
def test_realistic_rendered_parses_and_keeps_korean(filename, token):
    result = parse(DOCUMENTS_R / filename)
    assert result.error is None, f"{filename} 파싱 실패: {result.error}"
    assert token in result.text, f"{filename} 파싱 결과에 '{token}' 없음(폰트/렌더 깨짐 의심)"


@pytest.mark.parametrize("filename", ["02-hr-committee-minutes-2024-03.pdf",
                                      "06-teamlead-minutes-2024-06.pdf"])
def test_realistic_pdf_does_not_fall_back_to_ocr(filename):
    result = parse(DOCUMENTS_R / filename)
    assert result.used_ocr is False, f"{filename} 가 OCR 로 떨어짐 — 한글 폰트 임베드 실패"
    assert needs_ocr(result.text) is False
```

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/corpus/test_corpus_ko_wiki_realistic.py -k realistic_rendered -q`
Expected: FAIL — `documents-realistic/` 없음(파싱 결과 error 또는 FileNotFound).

- [ ] **Step 3: build.py 에 realistic 트리 지원을 추가한다**

`experiments/corpus-ko-wiki/build.py` 의 `build_all` 과 `__main__` 을 아래로 바꾼다(다른 함수·`FORMAT_MAP`·`render_*` 는 그대로):

```python
def build_all(root: Path = ROOT, *, realistic: bool = False) -> list[Path]:
    sources_dir = root / ("sources-realistic" if realistic else "sources")
    documents_dir = root / ("documents-realistic" if realistic else "documents")
    documents_dir.mkdir(exist_ok=True)
    written: list[Path] = []
    for slug, fmt in FORMAT_MAP.items():
        md_text = (sources_dir / f"{slug}.md").read_text(encoding="utf-8")
        out = documents_dir / f"{slug}.{fmt}"
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
    import argparse

    ap = argparse.ArgumentParser(description="corpus-ko-wiki 렌더")
    ap.add_argument("--realistic", action="store_true",
                    help="sources-realistic/ → documents-realistic/ 렌더")
    args = ap.parse_args()
    for path in build_all(realistic=args.realistic):
        print(f"wrote {path.relative_to(ROOT.parent.parent)}")
```

- [ ] **Step 4: realistic 산출물을 렌더한다**

Run: `uv run python experiments/corpus-ko-wiki/build.py --realistic`
Expected: `wrote experiments/corpus-ko-wiki/documents-realistic/…` 8줄.
`render_pdf` 가 `페이지에 텍스트가 넘침` 으로 죽으면 → `build.py` 의 `per_page = 42` 를 `36` 으로 낮추고 다시 실행한다.

- [ ] **Step 5: 통과 확인**

Run: `uv run pytest tests/corpus/test_corpus_ko_wiki_realistic.py -q`
Expected: PASS (파싱 8건 + no-OCR 2건 포함 전부 통과).

- [ ] **Step 6: 커밋**

```bash
git add experiments/corpus-ko-wiki/build.py \
        experiments/corpus-ko-wiki/documents-realistic \
        tests/corpus/test_corpus_ko_wiki_realistic.py
git commit -m "feat(corpus): realistic 세트 렌더(--realistic)와 파싱 회귀 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: ontology.md·README 문서화 + 문서 일관성 회귀

**Files:**
- Modify: `experiments/corpus-ko-wiki/ontology.md`
- Modify: `experiments/corpus-ko-wiki/README.md`
- Test: `tests/corpus/test_corpus_ko_wiki_realistic.py` (문서 일관성 추가)

**Interfaces:**
- Consumes: `REALISTIC_SLUGS` (Task 1).
- Produces: 없음(문서·테스트만).

- [ ] **Step 1: 문서 일관성 회귀를 먼저 추가한다 (실패 예정)**

`tests/corpus/test_corpus_ko_wiki_realistic.py` 하단에 추가:

```python
def test_readme_documents_realistic_set():
    readme = (CORPUS_ROOT / "README.md").read_text(encoding="utf-8")
    assert "sources-realistic" in readme
    assert "documents-realistic" in readme


def test_ontology_notes_shared_matrix():
    text = (CORPUS_ROOT / "ontology.md").read_text(encoding="utf-8")
    assert "realistic" in text, "ontology 에 realistic 세트가 매트릭스를 공유한다는 언급이 없다"
```

- [ ] **Step 2: 실패 확인**

Run: `uv run pytest tests/corpus/test_corpus_ko_wiki_realistic.py -k "readme_documents_realistic or ontology_notes" -q`
Expected: FAIL.

- [ ] **Step 3: ontology.md 에 공유 문장을 추가한다**

`experiments/corpus-ko-wiki/ontology.md` 상단 안내 문단 아래에 한 줄 추가:

```markdown
> 이 매트릭스는 두 세트(`sources/` 최소판, `sources-realistic/` realistic)가 공유한다.
> realistic 세트는 같은 슬러그·같은 장치를 유지한 채 실제 문서처럼 살을 붙이고 잡음을 섞는다.
```

- [ ] **Step 4: README 에 realistic 절을 추가한다**

`experiments/corpus-ko-wiki/README.md` 끝의 "벌크 확장 seam" 절 앞에 아래 절을 추가한다:

```markdown
## realistic 세트

`sources-realistic/`(SSOT) 는 위 8문서와 같은 슬러그·같은 장치를 유지하되, 실제 사내
문서처럼 목적·정의·부칙·서명란 등 골격과 무관 조항(잡음)을 더한 확장판이다. 최소판
`sources/` 는 빠른 회귀용으로 그대로 두고, realistic 은 시연 optics 와 "잡음 속 신호"
난이도 검증에 쓴다. 렌더는 다음으로 재현한다.

​```bash
cd ai
uv run python experiments/corpus-ko-wiki/build.py --realistic
​```

산출물은 `documents-realistic/` 에 커밋돼 있어 시연에서 다시 렌더할 필요는 없다.

### realistic 위키 생성 수동 확인 (로컬)

장치가 유지되므로 최소판과 같은 단언을 재사용한다. 최소판 검증 명령의 인자 경로를
`sources/` 에서 `sources-realistic/` 로 바꿔 실행한다.

​```bash
cd ai
uv run python experiments/backend_sim.py \
  --runtime claude-code --model claude-sonnet-4-6 \
  --root <임시경로> --report <임시경로>/report.json \
  experiments/corpus-ko-wiki/sources-realistic/01-service-rules-v1.md \
  experiments/corpus-ko-wiki/sources-realistic/02-hr-committee-minutes-2024-03.md \
  experiments/corpus-ko-wiki/sources-realistic/03-service-rules-amendment.md \
  experiments/corpus-ko-wiki/sources-realistic/04-business-trip-guide.md \
  experiments/corpus-ko-wiki/sources-realistic/05-expense-faq.md
​```

통과 기준(최소판과 동일): 연차 페이지가 20일 한 값으로 수렴, 출장비 페이지가 교통비·
식비·숙박비·한도 네 조각을 병합, `03` 재업로드 시 연차 페이지 중복 없음. 추가로 잡음
조항(복장·징계·조직개편·경조사 등)이 별도 위키 페이지로 새지 않았는지 육안 확인한다.
```

(위 코드펜스의 `​` 표시는 실제 파일에서는 일반 백틱 3개다 — README 안에서 중첩 코드블록을 그대로 쓴다.)

- [ ] **Step 5: 통과 확인 + 전체 회귀**

Run: `uv run pytest tests/corpus/test_corpus_ko_wiki_realistic.py -q`
Expected: PASS (전체).
Run: `uv run pytest -m "not ocr" -q`
Expected: 기준선과 동일하게 0 failures (778 passed 수준 + 신규 realistic 테스트 추가분).

- [ ] **Step 6: 커밋**

```bash
git add experiments/corpus-ko-wiki/ontology.md \
        experiments/corpus-ko-wiki/README.md \
        tests/corpus/test_corpus_ko_wiki_realistic.py
git commit -m "docs: corpus-ko-wiki realistic 세트 문서화(README·ontology)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 완료 기준 (전체)

- `sources-realistic/` 8건이 장치 verbatim·잡음·최소 길이 회귀를 통과한다.
- `documents-realistic/` 8건이 렌더돼 커밋되고, 파싱 회귀(한글 보존·PDF no-OCR)를 통과한다.
- `build.py --realistic` 로 산출물을 언제든 재생성할 수 있다.
- `ontology.md`·`README.md` 가 realistic 세트를 설명하고 문서 일관성 회귀를 통과한다.
- `uv run pytest -m "not ocr"` 기준선 대비 신규 실패 0.
- Jira S15P11B106-177 인수 기준(위키 생성 시 병합·모순·교차링크 발화 수동 확인)을 realistic 절차로 충족.
