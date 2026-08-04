import pytest

from document_parser.encoding import decode_csv_bytes


def test_utf8_bom_is_stripped_and_decoded():
    raw = b"\xef\xbb\xbf\xec\xa0\x9c\xeb\xaa\xa9,\xec\x9d\xbc\xec\xa0\x95\n"
    assert decode_csv_bytes(raw) == "제목,일정\n"


def test_plain_utf8_decodes_without_a_bom():
    text = "제목,시작,종료\n전사 워크샵,2026-08-05 14:00,2026-08-05 18:00\n"
    assert decode_csv_bytes(text.encode("utf-8")) == text


def test_cp949_falls_back_when_utf8_is_invalid():
    text = "제목,시작,종료\n전사 워크샵,2026-08-05 14:00,2026-08-05 18:00\n"
    assert decode_csv_bytes(text.encode("cp949")) == text


def test_neither_encoding_raises_value_error():
    # UTF-8 도 CP949 도 아닌 바이트열. 0x80 단독은 두 인코딩 모두에서 잘못된 시작
    # 바이트다.
    raw = b"\x80\x81\x82\x83"
    with pytest.raises(ValueError, match="인코딩을 판별할 수 없는 CSV입니다"):
        decode_csv_bytes(raw)
