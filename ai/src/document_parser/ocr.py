"""Tesseract-backed OCR helpers for PDF pages."""

import shutil
import subprocess

import pymupdf

from .errors import DocumentParseFailure


TESSERACT_LIST_LANGS_TIMEOUT_SECONDS = 5


def tesseract_available(language: str = "eng") -> bool:
    """Return whether Tesseract and all requested language data are available."""
    executable = shutil.which("tesseract")
    if executable is None:
        return False

    try:
        result = subprocess.run(
            [executable, "--list-langs"],
            capture_output=True,
            text=True,
            check=False,
            timeout=TESSERACT_LIST_LANGS_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False

    if result.returncode != 0:
        return False
    available_languages = set(result.stdout.splitlines()[1:])
    return all(item in available_languages for item in language.split("+"))


def ocr_page(page: pymupdf.Page, language: str = "eng", dpi: int = 300) -> str:
    """Extract a page with PyMuPDF's Tesseract OCR integration."""
    if not tesseract_available(language):
        raise DocumentParseFailure(
            "ocr_unavailable", f"Tesseract 언어 데이터가 없습니다: {language}"
        )

    try:
        text_page = page.get_textpage_ocr(language=language, dpi=dpi, full=True)
        return page.get_text("text", textpage=text_page, sort=True).strip()
    except Exception as exc:
        raise DocumentParseFailure("ocr_failed", "페이지 OCR에 실패했습니다.") from exc
