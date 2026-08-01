from pathlib import Path

CORPUS_ROOT = Path(__file__).resolve().parents[2] / "experiments" / "corpus-ko-wiki"

TOPICS = [
    "annual-leave", "half-day", "substitute-holiday", "business-trip-expense",
    "remote-work", "salary", "onboarding", "infosec",
]

FORMAT_MAP_SLUGS = [
    "01-service-rules-v1", "02-hr-committee-minutes-2024-03",
    "03-service-rules-amendment", "04-business-trip-guide",
    "05-expense-faq", "06-teamlead-minutes-2024-06",
    "07-onboarding-guide", "08-infosec-policy",
]


def test_ontology_lists_every_topic():
    text = (CORPUS_ROOT / "ontology.md").read_text(encoding="utf-8")
    for topic in TOPICS:
        assert topic in text, f"온톨로지에 주제 {topic} 가 없다"


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


BROKEN = CORPUS_ROOT / "documents" / "broken"


def test_broken_files_fail_with_clean_error_codes():
    docx_result = parse(BROKEN / "corrupt.docx")
    assert docx_result.error is not None
    assert docx_result.error.code == "corrupt_document"

    pdf_result = parse(BROKEN / "corrupt.pdf")
    assert pdf_result.error is not None
    assert pdf_result.error.code == "corrupt_document"


def test_readme_covers_every_source_and_topic():
    readme = (CORPUS_ROOT / "README.md").read_text(encoding="utf-8")
    for slug in FORMAT_MAP_SLUGS:
        assert slug in readme, f"README 에 {slug} 설명이 없다"
    for topic in TOPICS:
        assert topic in readme, f"README 에 주제 {topic} 가 없다"
