from io import BytesIO
from pathlib import Path
from unittest.mock import Mock

import pymupdf
import pytest
from PIL import Image, ImageDraw, ImageFont

from document_parser import parse
from document_parser.errors import DocumentParseFailure
from document_parser import ocr, pdf_parser
from document_parser.ocr import TESSERACT_LIST_LANGS_TIMEOUT_SECONDS


def test_tesseract_available_returns_bool():
    assert isinstance(ocr.tesseract_available(), bool)


def test_tesseract_available_returns_false_when_listing_languages_raises_os_error(
    monkeypatch: pytest.MonkeyPatch,
):
    monkeypatch.setattr(ocr.shutil, "which", lambda _name: "/usr/bin/tesseract")

    def raise_os_error(*_args, **_kwargs):
        raise OSError("Tesseract cannot be executed")

    monkeypatch.setattr(ocr.subprocess, "run", raise_os_error)

    assert ocr.tesseract_available() is False


def test_tesseract_available_returns_false_when_listing_languages_times_out(
    monkeypatch: pytest.MonkeyPatch,
):
    monkeypatch.setattr(ocr.shutil, "which", lambda _name: "/usr/bin/tesseract")

    mock_run = Mock(
        side_effect=ocr.subprocess.TimeoutExpired("tesseract --list-langs", 5)
    )
    monkeypatch.setattr(ocr.subprocess, "run", mock_run)

    assert ocr.tesseract_available() is False
    assert (
        mock_run.call_args.kwargs["timeout"]
        == TESSERACT_LIST_LANGS_TIMEOUT_SECONDS
    )


def test_ocr_page_reports_unavailable_without_using_page(
    monkeypatch: pytest.MonkeyPatch,
):
    class Page:
        def get_textpage_ocr(self, **_kwargs):
            raise AssertionError("OCR page should not be called")

    monkeypatch.setattr(ocr, "tesseract_available", lambda _language: False)

    with pytest.raises(DocumentParseFailure) as exc_info:
        ocr.ocr_page(Page())

    assert exc_info.value.code == "ocr_unavailable"
    assert exc_info.value.message == "Tesseract 언어 데이터가 없습니다: eng"


def test_ocr_page_converts_engine_error_to_structured_failure(
    monkeypatch: pytest.MonkeyPatch,
):
    class Page:
        def get_textpage_ocr(self, **_kwargs):
            raise RuntimeError("engine failure")

    monkeypatch.setattr(ocr, "tesseract_available", lambda _language: True)

    with pytest.raises(DocumentParseFailure) as exc_info:
        ocr.ocr_page(Page())

    assert exc_info.value.code == "ocr_failed"
    assert exc_info.value.message == "페이지 OCR에 실패했습니다."


def test_ocr_page_uses_tesseract_text_page_with_requested_options(
    monkeypatch: pytest.MonkeyPatch,
):
    class Page:
        def __init__(self):
            self.ocr_kwargs = None
            self.text_kwargs = None

        def get_textpage_ocr(self, **kwargs):
            self.ocr_kwargs = kwargs
            return "text-page"

        def get_text(self, *_args, **kwargs):
            self.text_kwargs = kwargs
            return "  Minimum paid leave is 25 days.  \n"

    page = Page()
    monkeypatch.setattr(ocr, "tesseract_available", lambda _language: True)

    result = ocr.ocr_page(page, language="kor+eng")

    assert result == "Minimum paid leave is 25 days."
    assert page.ocr_kwargs == {"language": "kor+eng", "dpi": 300, "full": True}
    assert page.text_kwargs == {"textpage": "text-page", "sort": True}


def _write_scan_pdf(path: Path) -> None:
    image = Image.new("RGB", (1800, 360), "white")
    draw = ImageDraw.Draw(image)
    draw.text((40, 120), "Minimum paid leave is 25 days", fill="black", font=ImageFont.load_default(size=48))
    buffer = BytesIO()
    image.save(buffer, format="PNG")

    document = pymupdf.open()
    page = document.new_page(width=600, height=120)
    page.insert_image(page.rect, stream=buffer.getvalue())
    document.save(path)
    document.close()


@pytest.mark.ocr
def test_parse_scan_pdf_uses_ocr_when_tesseract_is_available(tmp_path: Path):
    if not ocr.tesseract_available():
        pytest.skip("local Tesseract with eng traineddata is unavailable")
    path = tmp_path / "scan.pdf"
    _write_scan_pdf(path)

    result = parse(path)

    assert result.error is None
    assert result.used_ocr is True
    assert result.pages[0].method == "ocr"
    assert result.pages[0].quality_score == 0.95
    assert result.quality_score == 0.95
    assert "25 days" in result.text


def test_parse_scan_pdf_returns_ocr_failure_as_structured_error(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    path = tmp_path / "scan.pdf"
    _write_scan_pdf(path)

    def unavailable(_page, _language):
        raise DocumentParseFailure(
            "ocr_unavailable", "Tesseract 언어 데이터가 없습니다: eng"
        )

    monkeypatch.setattr(pdf_parser, "ocr_page", unavailable)

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "ocr_unavailable"
    assert result.error.failed_pages == (1,)


def test_parse_scan_pdf_marks_empty_ocr_output_as_low_quality(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    path = tmp_path / "scan.pdf"
    _write_scan_pdf(path)
    monkeypatch.setattr(pdf_parser, "ocr_page", lambda _page, _language: "")

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "native_extraction_empty"
    assert result.error.failed_pages == (1,)
    assert result.warnings == ("low_quality",)
