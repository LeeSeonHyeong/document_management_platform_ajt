"""Native UTF-8 parser for plain text and Markdown documents."""

from pathlib import Path

from .models import PageResult, ParseError, ParseResult


def parse_text(path: Path) -> ParseResult:
    """Read a UTF-8 text file without changing its contents."""
    try:
        with path.open(encoding="utf-8", newline="") as file:
            text = file.read()
    except UnicodeDecodeError:
        return ParseResult(
            error=ParseError("decode_failed", "UTF-8 텍스트를 읽을 수 없습니다.")
        )

    return ParseResult(
        text=text,
        pages=(PageResult(page=1, text=text, method="native", quality_score=1.0),),
        quality_score=1.0,
    )
