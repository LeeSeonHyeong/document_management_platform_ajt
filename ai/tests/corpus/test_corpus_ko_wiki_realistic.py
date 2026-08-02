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


def test_readme_documents_realistic_set():
    readme = (CORPUS_ROOT / "README.md").read_text(encoding="utf-8")
    assert "sources-realistic" in readme
    assert "documents-realistic" in readme


def test_ontology_notes_shared_matrix():
    text = (CORPUS_ROOT / "ontology.md").read_text(encoding="utf-8")
    assert "realistic" in text, "ontology 에 realistic 세트가 매트릭스를 공유한다는 언급이 없다"
