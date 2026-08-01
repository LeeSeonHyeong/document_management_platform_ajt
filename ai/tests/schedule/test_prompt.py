"""프롬프트가 규칙을 담고 있나. 모델을 부르지 않는다.

프롬프트 문장을 문자열로 검사하는 것은 취약하지만, 여기서 지키려는 것은 문장이 아니라
**규칙의 존재**다. 시각 변환 금지·기준 연도·종일 규칙이 빠지면 모델이 §2.1 의 실패로
되돌아간다.
"""

from datetime import datetime, timezone

from schedule_extractor.prompt import OUTPUT_SCHEMA, build_prompt

NOW = datetime(2026, 7, 29, 12, 0, tzinfo=timezone.utc)


def test_output_schema_has_no_status_field():
    """status 는 코드가 정한다 (설계 §6.3)."""
    assert "status" not in OUTPUT_SCHEMA["properties"]
    assert set(OUTPUT_SCHEMA["required"]) == {"schedules", "warnings"}


def test_output_schema_asks_for_local_naive_time():
    item = OUTPUT_SCHEMA["properties"]["schedules"]["items"]
    assert set(item["required"]) == {"title", "startLocal", "endLocal", "allDay"}
    for key in ("title", "content", "targetText", "location",
                "startLocal", "endLocal", "allDay"):
        assert key in item["properties"], key
    assert "startAt" not in item["properties"]


def test_prompt_carries_the_document():
    prompt = build_prompt("# 8월 일정\n- 8월 3일 회의\n", now=NOW)
    assert "# 8월 일정" in prompt
    assert "8월 3일 회의" in prompt


def test_prompt_states_the_reference_year_from_now():
    assert "2026" in build_prompt("문서", now=NOW)


def test_prompt_forbids_timezone_arithmetic():
    prompt = build_prompt("문서", now=NOW)
    assert "변환" in prompt
    assert "YYYY-MM-DDTHH:MM" in prompt


def test_prompt_states_the_all_day_rule():
    prompt = build_prompt("문서", now=NOW)
    assert "allDay" in prompt
    assert "00:00" in prompt


def test_prompt_reference_year_follows_kst_not_utc():
    """UTC 12월 31일 15:30 은 KST 로 다음 해 1월 1일 0시 30분이다."""
    prompt = build_prompt("문서", now=datetime(2026, 12, 31, 15, 30, tzinfo=timezone.utc))
    assert "2027" in prompt
    assert "2026" not in prompt
