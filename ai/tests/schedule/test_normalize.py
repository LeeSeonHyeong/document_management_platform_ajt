"""정규화 — 모델 출력을 계약 자료형으로.

LLM 을 부르지 않는다. 설계 §3.5 의 시각 규칙과 §3.3 의 경고 규칙이 전부 여기 있다.

**대체값 정책은 S15P11B106-79 에서 「버리기」를 대체했다.** 빠진 제목·시각은 항목을
떨어뜨리는 대신 `무제`·기준 시각으로 채운다 — 관리자가 고칠 수 있는 초안 한 건이
없는 일정보다 낫고, DB 가 그 세 칸을 `NOT NULL` 로 잡고 있어 공란도 불가능하다.
"""

from datetime import datetime, timedelta, timezone

import pytest

from schedule_extractor.normalize import FALLBACK_TITLE, normalize

NOW = datetime(2026, 7, 29, 3, 0, tzinfo=timezone.utc)  # KST 12:00


def _raw(**overrides):
    item = {"title": "하계 워크샵", "content": "전사 워크샵",
            "targetText": "개발부", "location": "속초",
            "startLocal": "2026-08-12T09:00", "endLocal": "2026-08-13T18:00",
            "allDay": False}
    item.update(overrides)
    return {"schedules": [item], "warnings": []}


def test_local_time_becomes_utc():
    """09:00 KST = 00:00Z. 모델에게 시키면 09:00Z 로 틀린다 (설계 §2.1)."""
    schedules, _ = normalize(_raw(), now=NOW)
    assert schedules[0].start_at == datetime(2026, 8, 12, 0, 0, tzinfo=timezone.utc)
    assert schedules[0].end_at == datetime(2026, 8, 13, 9, 0, tzinfo=timezone.utc)


def test_midnight_crosses_to_the_previous_utc_day():
    schedules, _ = normalize(
        _raw(startLocal="2026-08-12T00:00", endLocal="2026-08-12T08:00"), now=NOW)
    assert schedules[0].start_at == datetime(2026, 8, 11, 15, 0, tzinfo=timezone.utc)


def test_all_day_end_keeps_the_last_second():
    """23:59 은 하루의 마지막 60초를 잘라낸다. DATETIME(6) 이라 마이크로초를 담는다."""
    schedules, _ = normalize(
        _raw(startLocal="2026-08-25T00:00", endLocal="2026-08-25T23:59", allDay=True),
        now=NOW)
    assert schedules[0].end_at == datetime(
        2026, 8, 25, 14, 59, 59, 999999, tzinfo=timezone.utc)


def test_missing_end_gets_one_hour_and_a_warning():
    schedules, warnings = normalize(_raw(endLocal=""), now=NOW)
    assert schedules[0].end_at == datetime(2026, 8, 12, 1, 0, tzinfo=timezone.utc)
    assert any("종료" in warning for warning in warnings)


def test_all_day_without_end_fills_the_whole_day():
    """모델이 allDay 만 내고 endLocal 을 비우는 경우. 1시간이 아니라 그날 전체다."""
    schedules, warnings = normalize(
        _raw(startLocal="2026-08-25T00:00", endLocal="", allDay=True), now=NOW)
    assert schedules[0].end_at == datetime(
        2026, 8, 25, 14, 59, 59, 999999, tzinfo=timezone.utc)
    assert any("종일" in warning for warning in warnings)


def test_year_missing_uses_the_reference_year():
    schedules, warnings = normalize(
        _raw(startLocal="08-30T10:00", endLocal="08-30T11:00"), now=NOW)
    assert schedules[0].start_at.year == 2026
    assert any("연도" in warning for warning in warnings)


def test_year_inference_rolls_forward_when_too_far_past():
    """12월 문서의 1월 5일에 현재 연도를 넣으면 11개월 과거가 된다."""
    december = datetime(2026, 12, 20, 3, 0, tzinfo=timezone.utc)
    schedules, warnings = normalize(
        _raw(startLocal="01-05T10:00", endLocal="01-05T11:00"), now=december)
    assert schedules[0].start_at.year == 2027
    assert any("연도" in warning for warning in warnings)


def test_reference_year_follows_kst():
    """UTC 12월 31일 15:30 은 KST 로 2027년 1월 1일 0시 30분이다."""
    turn = datetime(2026, 12, 31, 15, 30, tzinfo=timezone.utc)
    schedules, _ = normalize(
        _raw(startLocal="01-05T10:00", endLocal="01-05T11:00"), now=turn)
    assert schedules[0].start_at.year == 2027


def test_reversed_period_is_pushed_out_not_dropped():
    """백엔드가 뒤집힌 기간을 거부하므로 그냥 둘 수 없다. 버리는 대신 종료를 민다."""
    raw = _raw()
    raw["schedules"].append({"title": "뒤집힌 일정",
                             "startLocal": "2026-08-20T16:00",
                             "endLocal": "2026-08-20T14:00", "allDay": False})
    schedules, warnings = normalize(raw, now=NOW)

    assert [item.title for item in schedules] == ["하계 워크샵", "뒤집힌 일정"]
    reversed_item = schedules[1]
    assert reversed_item.end_at == reversed_item.start_at + timedelta(hours=1)
    assert any("뒤집힌 일정" in warning for warning in warnings)


def test_blank_title_becomes_the_fallback_title():
    """버리면 관리자는 그 일정이 있었다는 사실조차 모른다. `무제` 로 살려 보낸다."""
    schedules, warnings = normalize(_raw(title="   "), now=NOW)

    assert len(schedules) == 1
    assert schedules[0].title == FALLBACK_TITLE
    assert schedules[0].start_at == datetime(2026, 8, 12, 0, 0, tzinfo=timezone.utc)
    assert any("제목" in warning for warning in warnings)


def test_missing_start_falls_back_to_the_reference_time():
    """DB 가 start_at 을 NOT NULL 로 잡아 공란으로 둘 수 없다. 기준 시각으로 채운다."""
    schedules, warnings = normalize(_raw(startLocal=""), now=NOW)

    assert len(schedules) == 1
    assert schedules[0].start_at == NOW  # KST 12:00 == 03:00Z, 초 이하 없음
    # 종료는 문서에 있었으므로 그대로 둔다 — 시작 하나가 빠졌다고 나머지를 버리지 않는다.
    assert schedules[0].end_at == datetime(2026, 8, 13, 9, 0, tzinfo=timezone.utc)
    assert any("시작 시각을 정하지 못해" in warning for warning in warnings)


def test_reference_fallback_drops_seconds():
    """관리자 화면의 입력칸이 분 단위라 초를 남기면 손대지 않은 초가 저장된다."""
    ragged = datetime(2026, 7, 29, 3, 0, 41, 987654, tzinfo=timezone.utc)
    schedules, _ = normalize(_raw(startLocal=""), now=ragged)

    assert schedules[0].start_at.second == 0
    assert schedules[0].start_at.microsecond == 0


def test_unparseable_date_keeps_the_item_and_names_the_text():
    """읽지 못한 원문을 경고에 싣는다 — 그 원문은 `_parse_local` 밖에 모른다."""
    schedules, warnings = normalize(_raw(startLocal="언젠가"), now=NOW)

    assert len(schedules) == 1
    assert schedules[0].start_at == NOW
    assert any("언젠가" in warning for warning in warnings)


def test_an_entirely_empty_item_still_becomes_one_draft():
    """모델이 껍데기만 낸 경우. 제목·시작·종료 셋 다 대체값으로 채워 초안을 만든다."""
    schedules, warnings = normalize({"schedules": [{}], "warnings": []}, now=NOW)

    assert len(schedules) == 1
    assert schedules[0].title == FALLBACK_TITLE
    assert schedules[0].start_at == NOW
    assert schedules[0].end_at == NOW + timedelta(hours=1)
    assert schedules[0].content is None
    assert schedules[0].target_text is None
    assert schedules[0].location is None
    assert any("제목" in warning for warning in warnings)
    assert any("시작 시각을 정하지 못해" in warning for warning in warnings)


def test_missing_content_and_target_are_warnings_not_drops():
    """FR-SCH-001 이 내용·대상 추출을 요구한다. 버리는 것이 더 어긋난다 (설계 §3.3)."""
    schedules, warnings = normalize(_raw(content="", targetText=""), now=NOW)
    assert len(schedules) == 1
    assert schedules[0].content is None
    assert schedules[0].target_text is None
    assert any("내용" in warning for warning in warnings)
    assert any("대상" in warning for warning in warnings)


def test_missing_location_is_silent():
    """장소는 FR-SCH-001 의 요구 대상이 아니다."""
    _, warnings = normalize(_raw(location=""), now=NOW)
    assert not any("장소" in warning for warning in warnings)


def test_order_is_renumbered_from_one():
    """모델이 order 를 안 내므로 정규화가 매긴다. 대체값을 쓴 항목도 자리를 지킨다."""
    raw = _raw()
    raw["schedules"] = [
        {"title": "첫째", "startLocal": "2026-08-03T10:00",
         "endLocal": "2026-08-03T11:00", "allDay": False},
        {"title": "시각이 깨진 것", "startLocal": "깨진 값",
         "endLocal": "2026-08-04T11:00", "allDay": False},
        {"title": "셋째", "startLocal": "2026-08-05T10:00",
         "endLocal": "2026-08-05T11:00", "allDay": False},
    ]
    schedules, _ = normalize(raw, now=NOW)
    assert [item.order for item in schedules] == [1, 2, 3]
    assert [item.title for item in schedules] == ["첫째", "시각이 깨진 것", "셋째"]


def test_model_warnings_are_kept():
    raw = _raw()
    raw["warnings"] = ["안전교육 종료 시각을 추정했습니다."]
    _, warnings = normalize(raw, now=NOW)
    assert "안전교육 종료 시각을 추정했습니다." in warnings


def test_empty_input_yields_nothing():
    schedules, warnings = normalize({"schedules": [], "warnings": []}, now=NOW)
    assert schedules == ()
    assert warnings == ()


@pytest.mark.parametrize("payload", [{}, {"schedules": None, "warnings": None}])
def test_missing_keys_do_not_crash(payload):
    """모델이 스키마를 어겨도 500 은 라우터가 낸다 — 여기서 터지면 안 된다."""
    schedules, warnings = normalize(payload, now=NOW)
    assert schedules == ()
    assert warnings == ()


def test_the_same_warning_is_not_repeated():
    """시작·종료가 같은 사유로 걸리면 `_parse_local` 이 두 번 불려 같은 문장이 두 번
    쌓인다. 두 문장이 알려주는 것은 하나다."""
    _, warnings = normalize(
        {"schedules": [{"title": "신년회", "startLocal": "01-05T10:00",
                        "endLocal": "01-05T12:00", "allDay": False}]},
        now=datetime(2026, 12, 20, 3, 0, tzinfo=timezone.utc))

    year_warnings = [w for w in warnings if "연도가 없어" in w]
    assert len(year_warnings) == 1, warnings
