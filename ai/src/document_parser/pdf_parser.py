"""Native PDF text extraction with OCR eligibility detection."""

from pathlib import Path

import pymupdf

from .errors import DocumentParseFailure
from .models import OcrEngine, PageResult, ParseError, ParseResult
from .ocr import ocr_page

# 원격 비전 OCR 에 넘길 페이지 렌더 해상도. 가독성엔 충분하고, 더 키우면 이미지 토큰·비용과
# GMS 요청 크기만 늘어난다.
_OCR_RENDER_DPI = 200


def _run_ocr(page: pymupdf.Page, language: str,
             ocr_engine: OcrEngine | None) -> str:
    """OCR 이 필요한 페이지의 텍스트를 얻는다. 엔진이 주어지면 원격 비전으로, 아니면
    로컬 Tesseract 로. 실패는 양쪽 다 `DocumentParseFailure` 로 올라온다."""
    if ocr_engine is None:
        return ocr_page(page, language)
    try:
        png_bytes = page.get_pixmap(dpi=_OCR_RENDER_DPI).tobytes("png")
    except Exception as exc:
        raise DocumentParseFailure(
            "ocr_failed", "페이지 이미지 렌더에 실패했습니다.") from exc
    return ocr_engine.ocr_image(png_bytes)


def needs_ocr(text: str) -> bool:
    """Return whether native text is too sparse or garbled to use."""
    compact = "".join(text.split())
    if len(compact) < 20:
        return True
    return compact.count("\ufffd") / len(compact) > 0.10


def parse_pdf(path: Path, language: str = "eng",
              ocr_engine: OcrEngine | None = None) -> ParseResult:
    """Extract PDF text natively, using OCR only for sparse pages."""

    try:
        document = pymupdf.open(path)
    except (pymupdf.FileDataError, RuntimeError, OSError):
        return ParseResult(
            error=ParseError("corrupt_document", "PDF 파일을 읽을 수 없습니다.")
        )

    try:
        pages: list[PageResult] = []
        failed_pages: list[int] = []
        failures: list[DocumentParseFailure] = []
        warnings: list[str] = []
        for page_number, page in enumerate(document, start=1):
            try:
                text = page.get_text("text", sort=True).strip()
            except Exception:
                failed_pages.append(page_number)
                failures.append(
                    DocumentParseFailure(
                        "corrupt_document", "PDF 파일을 읽을 수 없습니다."
                    )
                )
                warnings.append("page_extraction_failed")
                continue
            if needs_ocr(text):
                try:
                    text = _run_ocr(page, language, ocr_engine)
                except DocumentParseFailure as exc:
                    failed_pages.append(page_number)
                    failures.append(exc)
                    warnings.append(exc.code)
                    continue
                if not text:
                    failed_pages.append(page_number)
                    failures.append(
                        DocumentParseFailure(
                            "native_extraction_empty",
                            "일부 PDF 페이지에 OCR이 필요합니다.",
                        )
                    )
                    warnings.append("low_quality")
                    continue
                pages.append(
                    PageResult(
                        page=page_number,
                        text=text,
                        method="ocr",
                        quality_score=0.95,
                    )
                )
            else:
                pages.append(
                    PageResult(
                        page=page_number,
                        text=text,
                        method="native",
                        quality_score=1.0,
                    )
                )
    finally:
        document.close()

    text = "\n\f\n".join(page.text for page in pages)
    if failures:
        first_failure = failures[0]
        if pages:
            error_code = "partial_failure"
        else:
            error_code = first_failure.code
        return ParseResult(
            text=text,
            pages=tuple(pages),
            used_ocr=any(page.method == "ocr" for page in pages),
            quality_score=(
                sum(page.quality_score for page in pages) / len(pages) if pages else 0.0
            ),
            warnings=tuple(dict.fromkeys(warnings)),
            error=ParseError(
                error_code,
                first_failure.message,
                tuple(failed_pages),
            ),
        )

    return ParseResult(
        text=text,
        pages=tuple(pages),
        used_ocr=any(page.method == "ocr" for page in pages),
        quality_score=(
            sum(page.quality_score for page in pages) / len(pages) if pages else 0.0
        ),
    )
