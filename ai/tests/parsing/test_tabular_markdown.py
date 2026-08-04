import pytest

from document_parser.tabular_markdown import rows_to_markdown


def test_renders_a_header_and_two_data_rows():
    rows = [["제목", "시작", "종료"],
            ["전사 워크샵", "2026-08-05 14:00", "2026-08-05 18:00"],
            ["스프린트 회의", "2026-08-07 10:00", "2026-08-07 11:00"]]

    markdown = rows_to_markdown(rows)

    assert markdown == (
        "| 제목 | 시작 | 종료 |\n"
        "| --- | --- | --- |\n"
        "| 전사 워크샵 | 2026-08-05 14:00 | 2026-08-05 18:00 |\n"
        "| 스프린트 회의 | 2026-08-07 10:00 | 2026-08-07 11:00 |"
    )


def test_header_only_is_a_valid_table():
    assert rows_to_markdown([["제목", "시작"]]) == "| 제목 | 시작 |\n| --- | --- |"


def test_ragged_rows_are_padded_with_empty_cells():
    rows = [["제목", "시작", "종료"], ["워크샵", "2026-08-05"]]

    markdown = rows_to_markdown(rows)

    assert markdown.splitlines()[-1] == "| 워크샵 | 2026-08-05 |  |"


def test_embedded_newlines_become_spaces():
    rows = [["제목", "비고"], ["워크샵", "1부\n2부"]]

    markdown = rows_to_markdown(rows)

    assert markdown.splitlines()[-1] == "| 워크샵 | 1부 2부 |"


def test_no_rows_raises_value_error():
    with pytest.raises(ValueError, match="표로 인식할 내용이 없습니다"):
        rows_to_markdown([])


def test_a_literal_pipe_in_a_cell_is_escaped_not_left_as_a_column_separator():
    rows = [["부서", "비고"], ["개발부|기획부", "겸임"]]

    markdown = rows_to_markdown(rows)
    last_line = markdown.splitlines()[-1]

    assert last_line == "| 개발부\\|기획부 | 겸임 |"
    # 이스케이프된 파이프는 열 구분자가 아니므로 열 개수는 여전히 2개다.
    assert last_line.count(" | ") == 1


def test_rows_that_are_blank_in_every_cell_are_dropped():
    rows = [["제목", "비고"], ["", ""], ["워크샵", "확정"]]

    markdown = rows_to_markdown(rows)

    assert markdown == (
        "| 제목 | 비고 |\n"
        "| --- | --- |\n"
        "| 워크샵 | 확정 |"
    )


def test_rows_of_only_whitespace_are_also_dropped():
    rows = [["제목"], ["   "], ["워크샵"]]

    markdown = rows_to_markdown(rows)

    assert markdown == "| 제목 |\n| --- |\n| 워크샵 |"


def test_all_blank_rows_leave_nothing_and_raise_value_error():
    with pytest.raises(ValueError, match="표로 인식할 내용이 없습니다"):
        rows_to_markdown([[""], ["", ""], []])
