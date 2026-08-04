"""CSV → Markdown. 일정 원본문서에서만 쓴다 (위키 원본은 CSV를 허용하지 않는다)."""

import csv
import io
from pathlib import Path

from .encoding import decode_csv_bytes
from .models import PageResult, ParseError, ParseResult
from .tabular_markdown import rows_to_markdown


def parse_csv(path: Path) -> ParseResult:
    raw = path.read_bytes()
    try:
        text = decode_csv_bytes(raw)
    except ValueError:
        return ParseResult(
            error=ParseError("encoding_undetected", "인코딩을 판별할 수 없는 CSV입니다.")
        )

    rows = list(csv.reader(io.StringIO(text)))

    try:
        markdown = rows_to_markdown(rows)
    except ValueError:
        return ParseResult(
            error=ParseError("not_tabular", "표로 인식할 수 없는 CSV입니다.")
        )

    return ParseResult(
        text=markdown,
        pages=(PageResult(page=1, text=markdown, method="native", quality_score=1.0),),
        quality_score=1.0,
    )
