"""Native DOCX paragraph and table extraction."""

import re
from pathlib import Path
from zipfile import BadZipFile

from docx import Document
from docx.opc.exceptions import PackageNotFoundError
from docx.table import Table
from docx.text.paragraph import Paragraph
from lxml.etree import XMLSyntaxError

from .models import PageResult, ParseError, ParseResult


_HEADING_STYLE = re.compile(r"^(?:Heading|제목)\s*(\d+)$")


def _heading_level(paragraph: Paragraph) -> int | None:
    style = paragraph.style
    if style is None or style.name is None:
        return None
    match = _HEADING_STYLE.match(style.name)
    if match is None:
        return None
    return min(int(match.group(1)), 6)


def _native_lines(document: Document) -> list[str]:
    lines: list[str] = []

    for block in document.iter_inner_content():
        if isinstance(block, Paragraph):
            text = block.text.strip()
            if text:
                level = _heading_level(block)
                if level is not None:
                    text = f"{'#' * level} {text}"
                lines.append(text)
        elif isinstance(block, Table):
            for row in block.rows:
                seen_cells: set[int] = set()
                cells = []
                for cell in row.cells:
                    if id(cell._tc) in seen_cells:
                        continue
                    seen_cells.add(id(cell._tc))
                    cells.append(cell.text.strip().replace("\n", " "))
                if any(cells):
                    lines.append(" | ".join(cells))

    return lines


def parse_docx(path: Path, language: str = "eng") -> ParseResult:
    """Extract DOCX paragraphs and tables in their document order."""
    del language

    try:
        document = Document(path)
    except (
        BadZipFile,
        PackageNotFoundError,
        ValueError,
        KeyError,
        OSError,
        XMLSyntaxError,
    ):
        return ParseResult(
            error=ParseError("corrupt_document", "DOCX 파일을 읽을 수 없습니다.")
        )

    lines = _native_lines(document)
    if not lines:
        return ParseResult(
            error=ParseError("native_extraction_empty", "DOCX에서 텍스트를 찾지 못했습니다.")
        )

    text = "\n".join(lines)
    return ParseResult(
        text=text,
        pages=(PageResult(page=1, text=text, method="native", quality_score=1.0),),
        quality_score=1.0,
    )
