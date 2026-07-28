"""Top-level document parser dispatcher."""

from pathlib import Path

from .docx_parser import parse_docx
from .models import ParseError, ParseOptions, ParseResult
from .pdf_parser import parse_pdf
from .text_parser import parse_text


SUPPORTED = frozenset({".txt", ".md", ".pdf", ".docx"})


def parse(file_path: str | Path, options: ParseOptions | None = None) -> ParseResult:
    """Parse a supported document once a format-specific parser is connected."""
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
        return parse_pdf(path, language=options.ocr_language)

    return ParseResult(
        error=ParseError("parser_unavailable", "해당 형식의 파서가 아직 연결되지 않았습니다.")
    )
