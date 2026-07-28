from pathlib import Path

import pytest

from document_parser import ParseOptions, parse
from document_parser.normalize import normalize_text


@pytest.mark.parametrize("suffix", [".txt", ".md", ".TXT", ".MD"])
def test_parse_utf8_text_and_markdown_verbatim(tmp_path: Path, suffix: str):
    text = "# Time off\n\nMinimum 25 days.\n"
    path = tmp_path / f"time-off{suffix}"
    path.write_text(text, encoding="utf-8")

    result = parse(path)

    assert result.error is None
    assert result.text == text
    assert len(result.pages) == 1
    assert result.pages[0].page == 1
    assert result.pages[0].text == text
    assert result.pages[0].method == "native"
    assert result.pages[0].quality_score == 1.0
    assert result.quality_score == 1.0


def test_parse_invalid_utf8_returns_decode_failed(tmp_path: Path):
    path = tmp_path / "bad.txt"
    path.write_bytes(b"\xff\xfe\x00")

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "decode_failed"
    assert result.error.message == "UTF-8 텍스트를 읽을 수 없습니다."


def test_parse_text_preserves_crlf_and_cr_verbatim(tmp_path: Path):
    text = "line one\r\nline two\r"
    path = tmp_path / "line-endings.txt"
    path.write_bytes(text.encode("utf-8"))

    result = parse(path)

    assert result.error is None
    assert result.text == text
    assert result.pages[0].text == text


def test_normalize_text():
    assert normalize_text(" A\r\n\r\nB\t C ") == "A\nB C"
    assert normalize_text("A\rB") == "A\nB"
    assert normalize_text("A\u00a0\u2009B") == "A B"


def test_text_parse_accepts_ocr_language_without_changing_result(tmp_path: Path):
    text = "한국어와 English\n"
    path = tmp_path / "sample.txt"
    path.write_text(text, encoding="utf-8")

    result = parse(path, ParseOptions(ocr_language="kor+eng"))

    assert result.error is None
    assert result.text == text
