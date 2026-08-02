from pathlib import Path

import pymupdf
import pytest

from document_parser import ParseOptions, parse
from document_parser import pdf_parser
from document_parser.errors import DocumentParseFailure
from document_parser.pdf_parser import needs_ocr


def _write_pdf(path: Path, *texts: str) -> None:
    document = pymupdf.open()
    for text in texts:
        page = document.new_page()
        if text:
            page.insert_text((72, 72), text)
    document.save(path)
    document.close()


def test_scanned_page_uses_the_injected_vision_ocr_engine(tmp_path: Path):
    """OCR 이 필요한 페이지는 주입된 비전 엔진으로 읽는다 — 로컬 Tesseract 를 타지 않는다
    (S15P11B106-180). 엔진에는 페이지 PNG 가 넘어가고, 그 반환 텍스트가 결과가 된다."""
    path = tmp_path / "scan.pdf"
    _write_pdf(path, "")  # 텍스트 없는 페이지 → needs_ocr

    class FakeEngine:
        def __init__(self) -> None:
            self.calls = 0

        def ocr_image(self, png_bytes: bytes) -> str:
            self.calls += 1
            assert png_bytes[:4] == b"\x89PNG"  # 페이지가 PNG 로 렌더돼 넘어온다
            return "회의 운영 세칙"

    engine = FakeEngine()
    result = parse(path, ParseOptions(ocr_engine=engine))

    assert result.error is None
    assert engine.calls == 1
    assert "회의 운영 세칙" in result.text
    assert result.used_ocr is True
    assert result.pages[0].method == "ocr"


def test_injected_vision_ocr_failure_is_a_structured_error(tmp_path: Path):
    """엔진이 DocumentParseFailure 를 올리면 Tesseract 실패와 같은 경로로 흡수한다."""
    path = tmp_path / "scan.pdf"
    _write_pdf(path, "")

    class FailingEngine:
        def ocr_image(self, png_bytes: bytes) -> str:
            raise DocumentParseFailure("ocr_failed", "비전 OCR 호출 실패")

    result = parse(path, ParseOptions(ocr_engine=FailingEngine()))

    assert result.error is not None
    assert result.error.code == "ocr_failed"


def test_parse_native_pdf_page(tmp_path: Path):
    path = tmp_path / "policy.pdf"
    _write_pdf(path, "Minimum paid leave is 25 days.")

    result = parse(path)

    assert result.error is None
    assert "25 days" in result.text
    assert result.used_ocr is False
    assert result.quality_score == 1.0
    assert len(result.pages) == 1
    assert result.pages[0].page == 1
    assert result.pages[0].method == "native"
    assert result.pages[0].quality_score == 1.0


def test_parse_native_pdf_pages_preserves_page_order(tmp_path: Path):
    path = tmp_path / "ordered.pdf"
    first = "First policy page has enough readable content."
    second = "Second policy page has enough readable content."
    _write_pdf(path, first, second)

    result = parse(path)

    assert result.error is None
    assert [page.page for page in result.pages] == [1, 2]
    assert [page.text for page in result.pages] == [first, second]
    assert result.text == f"{first}\n\f\n{second}"
    assert result.text.index(first) < result.text.index(second)


def test_needs_ocr_detects_short_or_garbled_text():
    assert needs_ocr("") is True
    assert needs_ocr("abc") is True
    assert needs_ocr("This readable sentence has enough useful words.") is False
    assert needs_ocr("\ufffd" * 20) is True
    assert needs_ocr("   \n\t ") is True
    assert needs_ocr("\ufffd" * 11 + "a" * 89) is True


@pytest.mark.parametrize("contents", [b"not pdf", b""])
def test_corrupt_pdf_returns_corrupt_document_error(tmp_path: Path, contents: bytes):
    path = tmp_path / "broken.pdf"
    path.write_bytes(contents)

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "corrupt_document"
    assert result.error.message == "PDF 파일을 읽을 수 없습니다."


def test_pdf_extension_is_case_insensitive_and_accepts_ocr_language(tmp_path: Path):
    path = tmp_path / "policy.PDF"
    _write_pdf(path, "Minimum paid leave is 25 days.")

    result = parse(path, ParseOptions(ocr_language="kor+eng"))

    assert result.error is None
    assert result.pages[0].method == "native"


def test_pdf_with_a_blank_page_preserves_native_pages_when_ocr_is_unavailable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    path = tmp_path / "mixed.pdf"
    _write_pdf(path, "Minimum paid leave is 25 days.", "")
    monkeypatch.setattr(
        pdf_parser,
        "ocr_page",
        lambda _page, _language: (_ for _ in ()).throw(
            DocumentParseFailure(
                "ocr_unavailable", "Tesseract 언어 데이터가 없습니다: eng"
            )
        ),
    )

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "partial_failure"
    assert result.error.message == "Tesseract 언어 데이터가 없습니다: eng"
    assert result.error.failed_pages == (2,)
    assert result.text == "Minimum paid leave is 25 days."
    assert len(result.pages) == 1
    assert result.pages[0].page == 1
    assert result.pages[0].method == "native"
    assert result.used_ocr is False
    assert result.quality_score == 1.0
    assert result.warnings == ("ocr_unavailable",)


def test_fully_blank_pdf_returns_ocr_unavailable_when_tesseract_is_missing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    path = tmp_path / "blank.pdf"
    _write_pdf(path, "")
    monkeypatch.setattr(
        pdf_parser,
        "ocr_page",
        lambda _page, _language: (_ for _ in ()).throw(
            DocumentParseFailure(
                "ocr_unavailable", "Tesseract 언어 데이터가 없습니다: eng"
            )
        ),
    )

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "ocr_unavailable"
    assert result.error.message == "Tesseract 언어 데이터가 없습니다: eng"
    assert result.error.failed_pages == (1,)
    assert result.pages == ()
    assert result.warnings == ("ocr_unavailable",)


class _PageWithExtractionFailure:
    def get_text(self, *_args, **_kwargs) -> str:
        raise RuntimeError("simulated page extraction failure")


class _PageWithTypeError:
    def get_text(self, *_args, **_kwargs) -> str:
        raise TypeError("simulated unexpected page extraction failure")


class _ReadablePage:
    def __init__(self, text: str) -> None:
        self.text = text

    def get_text(self, *_args, **_kwargs) -> str:
        return self.text


class _FakeDocument:
    def __init__(self, *pages: object) -> None:
        self.pages = pages
        self.closed = False

    def __iter__(self):
        return iter(self.pages)

    def close(self) -> None:
        self.closed = True


def test_page_extraction_error_returns_corrupt_document(monkeypatch: pytest.MonkeyPatch):
    document = _FakeDocument(_PageWithExtractionFailure())
    monkeypatch.setattr(pdf_parser.pymupdf, "open", lambda _path: document)

    result = pdf_parser.parse_pdf(Path("broken-page.pdf"))

    assert document.closed is True
    assert result.error is not None
    assert result.error.code == "corrupt_document"
    assert result.error.message == "PDF 파일을 읽을 수 없습니다."
    assert result.error.failed_pages == (1,)
    assert result.pages == ()


def test_unexpected_page_extraction_error_returns_corrupt_document(
    monkeypatch: pytest.MonkeyPatch,
):
    document = _FakeDocument(_PageWithTypeError())
    monkeypatch.setattr(pdf_parser.pymupdf, "open", lambda _path: document)

    result = pdf_parser.parse_pdf(Path("unexpected-broken-page.pdf"))

    assert document.closed is True
    assert result.error is not None
    assert result.error.code == "corrupt_document"
    assert result.error.message == "PDF 파일을 읽을 수 없습니다."
    assert result.error.failed_pages == (1,)
    assert result.pages == ()


def test_second_page_extraction_error_preserves_prior_page(
    monkeypatch: pytest.MonkeyPatch,
):
    first = "First policy page has enough readable content."
    document = _FakeDocument(_ReadablePage(first), _PageWithExtractionFailure())
    monkeypatch.setattr(pdf_parser.pymupdf, "open", lambda _path: document)

    result = pdf_parser.parse_pdf(Path("partially-broken.pdf"))

    assert document.closed is True
    assert result.error is not None
    assert result.error.code == "partial_failure"
    assert result.error.message == "PDF 파일을 읽을 수 없습니다."
    assert result.error.failed_pages == (2,)
    assert result.text == first
    assert [page.page for page in result.pages] == [1]
    assert result.warnings == ("page_extraction_failed",)
