"""표 데이터(행·열의 문자열) → Markdown 표. CSV·XLSX 파서가 공유한다.

둘의 차이는 앞단(디코딩 vs 워크북 읽기)에만 있고, "행 나열을 Markdown 표로 만든다"는
로직은 완전히 같다.
"""


def rows_to_markdown(rows: list[list[str]]) -> str:
    rows = [row for row in rows if any(cell.strip() for cell in row)]
    if not rows:
        raise ValueError("표로 인식할 내용이 없습니다")

    width = max(len(row) for row in rows)
    padded = [
        [_escape_cell(cell) for cell in row] + [""] * (width - len(row))
        for row in rows
    ]

    lines = ["| " + " | ".join(padded[0]) + " |",
             "| " + " | ".join(["---"] * width) + " |"]
    for row in padded[1:]:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def _escape_cell(value: str) -> str:
    """줄바꿈은 공백으로, `|`는 `\\|`로 바꾼다 — 둘 다 Markdown 표 문법이 셀 안에
    그대로 담지 못하는 문자다. `|`를 그대로 두면 셀 하나가 여러 열로 갈린다."""
    value = " ".join(value.splitlines())
    return value.replace("|", "\\|")
