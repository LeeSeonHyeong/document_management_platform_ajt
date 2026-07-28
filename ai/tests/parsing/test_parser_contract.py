from pathlib import Path

import pytest

from document_parser import parse


def test_missing_file_returns_stable_error(tmp_path: Path):
    result = parse(tmp_path / "missing.pdf")

    assert result.text == ""
    assert result.pages == ()
    assert result.error is not None
    assert result.error.code == "file_not_found"


def test_unsupported_extension_returns_stable_error(tmp_path: Path):
    path = tmp_path / "sample.exe"
    path.write_bytes(b"MZ")

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "unsupported_file_type"


def test_corrupt_pdf_returns_stable_error(tmp_path: Path):
    path = tmp_path / "sample.pdf"
    path.write_text("sample")

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "corrupt_document"


@pytest.mark.parametrize("suffix", [".PDF"])
def test_corrupt_pdf_extension_is_case_insensitive(tmp_path: Path, suffix: str):
    path = tmp_path / f"sample{suffix}"
    path.write_text("sample")

    result = parse(path)

    assert result.error is not None
    assert result.error.code == "corrupt_document"
