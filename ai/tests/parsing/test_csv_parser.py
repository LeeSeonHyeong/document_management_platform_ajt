from pathlib import Path

from document_parser.csv_parser import parse_csv

REPO_ROOT = Path(__file__).resolve().parents[3]
SAMPLE_SCHEDULE_CSV = REPO_ROOT / "docs" / "api" / "testfiles" / "sample-schedule.csv"


def test_the_real_contract_fixture_parses_into_a_markdown_table():
    result = parse_csv(SAMPLE_SCHEDULE_CSV)

    assert result.error is None
    assert result.text == (
        "| 제목 | 시작 | 종료 | 장소 | 대상 |\n"
        "| --- | --- | --- | --- | --- |\n"
        "| 전사 워크샵 | 2026-08-05 14:00 | 2026-08-05 18:00 | 본사 대강당 | 전 직원 |\n"
        "| 스프린트 회의 | 2026-08-07 10:00 | 2026-08-07 11:00 | 회의실 A | 개발부 |"
    )


def test_cp949_encoded_csv_decodes_correctly(tmp_path: Path):
    text = "제목,시작,종료\n경비 정산 마감,2026-08-12 09:30,2026-08-12 10:00\n"
    path = tmp_path / "schedule.csv"
    path.write_bytes(text.encode("cp949"))

    result = parse_csv(path)

    assert result.error is None
    assert "경비 정산 마감" in result.text


def test_a_quoted_field_with_an_embedded_comma_is_one_cell(tmp_path: Path):
    text = '제목,장소\n워크샵,"본사, 3층 대강당"\n'
    path = tmp_path / "schedule.csv"
    path.write_text(text, encoding="utf-8")

    result = parse_csv(path)

    assert result.error is None
    assert "| 워크샵 | 본사, 3층 대강당 |" in result.text


def test_header_only_csv_is_not_an_error(tmp_path: Path):
    path = tmp_path / "schedule.csv"
    path.write_text("제목,시작,종료\n", encoding="utf-8")

    result = parse_csv(path)

    assert result.error is None
    assert result.text == "| 제목 | 시작 | 종료 |\n| --- | --- | --- |"


def test_an_empty_csv_is_not_tabular(tmp_path: Path):
    path = tmp_path / "empty.csv"
    path.write_text("", encoding="utf-8")

    result = parse_csv(path)

    assert result.error is not None
    assert result.error.code == "not_tabular"


def test_an_all_comma_blank_row_is_dropped_like_xlsx(tmp_path: Path):
    """`,,` 은 `csv.reader`가 `['', '', '']`(참으로 평가됨)를 내놓지만, 모든 셀이
    비어 있으므로 XLSX 와 동일하게 표에서 제외돼야 한다 (파서 간 동작 통일)."""
    text = "제목,시작,종료\n,,\n워크샵,2026-08-05,2026-08-05\n"
    path = tmp_path / "schedule.csv"
    path.write_text(text, encoding="utf-8")

    result = parse_csv(path)

    assert result.error is None
    assert result.text == (
        "| 제목 | 시작 | 종료 |\n"
        "| --- | --- | --- |\n"
        "| 워크샵 | 2026-08-05 | 2026-08-05 |"
    )


def test_a_csv_of_only_blank_rows_is_not_tabular(tmp_path: Path):
    path = tmp_path / "blank.csv"
    path.write_text(",,\n,,\n", encoding="utf-8")

    result = parse_csv(path)

    assert result.error is not None
    assert result.error.code == "not_tabular"


def test_an_undetectable_encoding_is_reported(tmp_path: Path):
    path = tmp_path / "bad.csv"
    path.write_bytes(b"\x80\x81\x82\x83")

    result = parse_csv(path)

    assert result.error is not None
    assert result.error.code == "encoding_undetected"
