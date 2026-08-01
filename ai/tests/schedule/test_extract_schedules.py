"""`extract_schedules` 파이프라인 — provider + normalize + 날짜 근거 검사.

날짜 없는 문서에서 나온 일정은 모델이 만들어낸 것이다 (NFR-AI-002). 실측에서
`qwen2.5:7b-instruct` 가 날짜 없는 안내문에 `{연도}-01-01` 짜리 일정을 발명했다 —
프롬프트가 금지해도 어긴다. 프롬프트를 손대지 않는다는 원칙("모델은 판단하고 코드는
계산한다")에 따라 이 검사는 코드에 둔다.
"""

from datetime import datetime, timezone

from schedule_extractor import extract_schedules

NOW = datetime(2026, 7, 29, 3, 0, tzinfo=timezone.utc)


class _Fabricating:
    """날짜 없는 문서에도 일정을 낸다고 가정한 가짜 provider."""

    async def complete_json(self, prompt, schema):
        return {"schedules": [{"title": "신년 행사",
                               "startLocal": "2026-01-01T00:00",
                               "endLocal": "2026-01-01T23:59",
                               "allDay": True}],
                "warnings": []}


async def test_schedules_from_a_document_without_dates_are_discarded():
    """날짜 없는 문서에서 나온 일정은 모델이 만들어낸 것이다 (NFR-AI-002)."""
    result = await extract_schedules(
        "# 사내 편의시설 안내\n카페테리아는 본사 2층에 있습니다.\n",
        now=NOW, provider=_Fabricating())

    assert result.schedules == ()
    assert result.raw_count == 0
    assert any("날짜가 없는데" in warning for warning in result.warnings)


async def test_schedules_from_a_document_with_dates_survive():
    """날짜가 있는 문서는 이 검사에 걸리지 않는다 — 같은 가짜 provider 라도 살아남는다."""
    result = await extract_schedules(
        "# 신년 행사 안내\n1월 1일에 시무식을 진행합니다.\n",
        now=NOW, provider=_Fabricating())

    assert len(result.schedules) == 1
    assert result.raw_count == 1


class _RealScheduleFromScatteredDate:
    """월과 일이 떨어져 적힌 문서에서 모델이 문맥으로 정당하게 뽑은 진짜 일정."""

    async def complete_json(self, prompt, schema):
        return {"schedules": [{"title": "워크숍",
                               "startLocal": "2026-08-12T09:00",
                               "endLocal": "2026-08-12T11:00",
                               "allDay": False}],
                "warnings": []}


async def test_schedule_survives_when_month_and_day_are_written_apart():
    """`has_date_pattern` 은 False 지만 `has_any_date_evidence` 는 True 여야 한다.

    `has_date_pattern` 으로 근거 검사를 했을 때 실제로 샜던 문서다 — "8월"
    (제목)과 "12일"(본문)이 떨어져 있어 엄격한 검사가 안 걸리고 진짜 일정이
    버려졌다. 이 테스트가 그 회귀를 막는다.
    """
    result = await extract_schedules(
        "# 8월 행사 안내\n- 12일 워크숍 09:00 ~ 11:00\n",
        now=NOW, provider=_RealScheduleFromScatteredDate())

    assert len(result.schedules) == 1
    assert result.schedules[0].title == "워크숍"
    assert result.raw_count == 1
