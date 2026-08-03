"""모델 출력을 계약 자료형으로. LLM 을 부르지 않는다.

**여기가 계산을 맡는다** — 모델에게 시간 산술을 시키면 틀린다 (설계 §2.1).
KST→UTC 변환·연도 추론·순서 부여·대체값 채우기·누락 경고가 전부 여기 있다.

**빠진 값은 버리지 않고 대체값으로 채운다.** 제목이 없으면 `무제`, 시각이 없으면 기준
시각이다. 관리자가 초안을 건별로 검토·수정·승인하므로(FR-SCH-002·003) 「고칠 초안 한
건」이 「없는 일정」보다 낫다 — 버리면 관리자는 그 일정이 있었다는 사실조차 모른다.

DB 가 `title`·`start_at`·`end_at` 을 `NOT NULL` 로 잡고 있어(`docs/db/erd.sql`) 공란으로
둘 수도 없다. 백엔드도 셋 중 하나가 비면 응답 전체를 버린다
(`RestClientAiClient.isInvalid`).

대체값을 쓴 사유는 전부 `warnings` 에 남는다. **다만 지금 그 경고는 관리자에게 닿지
않는다** — 백엔드가 응답에 싣지 않는다(`ScheduleSourceService`). 노출은 S15P11B106-79
인수 기준에 있는 별도 작업이다. 그때까지 관리자가 알아채는 단서는 제목 `무제` 뿐이다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from . import ExtractedSchedule

KST = ZoneInfo("Asia/Seoul")

ROLL_FORWARD_MARGIN = timedelta(days=90)
"""추론한 연도로 계산한 날짜가 기준일보다 이만큼 과거면 다음 해로 본다.

12월 문서의 `1월 5일` 에 현재 연도를 넣으면 11개월 과거가 된다 (설계 §3.5).
"""

DEFAULT_DURATION = timedelta(hours=1)
"""시작 시각은 있고 종료가 없을 때 채우는 길이."""

FALLBACK_TITLE = "무제"
"""제목을 찾지 못했을 때 쓰는 제목.

빈 문자열이 아니라 눈에 띄는 낱말이어야 한다 — 관리자 목록에서 이것만이 「AI 가 못
채운 초안」이라는 단서다 (모듈 docstring).
"""


def normalize(raw: dict, *,
              now: datetime) -> tuple[tuple[ExtractedSchedule, ...], tuple[str, ...]]:
    reference = now.astimezone(KST)
    warnings: list[str] = list(raw.get("warnings") or ())
    kept: list[ExtractedSchedule] = []

    for item in raw.get("schedules") or ():
        kept.append(_one(item, reference=reference, warnings=warnings))

    renumbered = tuple(
        ExtractedSchedule(order=index, title=schedule.title, content=schedule.content,
                          target_text=schedule.target_text, location=schedule.location,
                          start_at=schedule.start_at, end_at=schedule.end_at)
        for index, schedule in enumerate(kept, start=1))
    return renumbered, _deduplicated(warnings)


def _deduplicated(warnings: list[str]) -> tuple[str, ...]:
    """같은 문장을 두 번 담지 않는다. 순서는 처음 나온 자리를 지킨다.

    시작과 종료가 같은 사유로 걸리면(연도 없음 등) `_parse_local` 이 두 번 불려 같은
    문장이 두 번 쌓인다. 두 문장이 알려주는 것은 하나이므로 읽는 쪽에는 잡음이다.
    제목이 문장에 들어 있어 서로 다른 일정의 경고가 같아질 일은 없다.
    """
    seen: set[str] = set()
    unique: list[str] = []
    for warning in warnings:
        if warning not in seen:
            seen.add(warning)
            unique.append(warning)
    return tuple(unique)


def _one(item: dict, *, reference: datetime,
         warnings: list[str]) -> ExtractedSchedule:
    """일정 1건. 무엇이 빠져 있어도 초안 하나를 만든다 (모듈 docstring)."""
    title = _clean(item.get("title"))
    if title is None:
        title = FALLBACK_TITLE
        warnings.append(f"제목이 없는 항목의 제목을 '{FALLBACK_TITLE}'(으)로 두었습니다.")

    start = _parse_local(item.get("startLocal"), reference=reference, warnings=warnings,
                         title=title, field="시작")
    if start is None:
        # 기준 시각으로 채운다. 초 이하를 떨어뜨리는 것은 관리자 화면의 입력칸이 분
        # 단위라, 남겨두면 관리자가 손대지 않은 초가 그대로 저장되기 때문이다.
        start = reference.replace(second=0, microsecond=0)
        # 「없어」가 아니라 「정하지 못해」다 — 비어 있었을 수도, 읽히지 않았을 수도
        # 있다. 후자면 `_parse_local` 이 원문을 실은 줄을 이미 남겼다.
        warnings.append(f"'{title}'의 시작 시각을 정하지 못해 현재 시각으로 두었습니다.")

    all_day = bool(item.get("allDay"))
    end = _parse_local(item.get("endLocal"), reference=reference, warnings=warnings,
                       title=title, field="종료")
    if end is None:
        if all_day:
            end = start.replace(hour=23, minute=59, second=59, microsecond=999_999)
            warnings.append(f"'{title}'이(가) 종일 일정인데 종료 시각이 없어 그날 끝으로 두었습니다.")
        else:
            end = start + DEFAULT_DURATION
            warnings.append(f"'{title}'의 종료 시각이 없어 시작 후 1시간으로 두었습니다.")
    elif all_day:
        end = end.replace(hour=23, minute=59, second=59, microsecond=999_999)

    if end < start:
        # 백엔드가 뒤집힌 기간을 거부하므로(`RestClientAiClient.isInvalid`) 그냥 둘 수
        # 없다. 버리는 대신 종료를 밀어 초안을 살린다 — 관리자가 고칠 수 있다.
        warnings.append(f"'{title}'의 종료가 시작보다 앞서 시작 후 1시간으로 두었습니다.")
        end = start + DEFAULT_DURATION

    content = _clean(item.get("content"))
    if content is None:
        warnings.append(f"'{title}'의 내용을 문서에서 찾지 못했습니다.")
    target_text = _clean(item.get("targetText"))
    if target_text is None:
        warnings.append(f"'{title}'의 대상을 문서에서 찾지 못했습니다.")

    return ExtractedSchedule(
        order=0,  # normalize 가 마지막에 1..N 으로 다시 매긴다
        title=title, content=content, target_text=target_text,
        location=_clean(item.get("location")),
        start_at=start.astimezone(timezone.utc),
        end_at=end.astimezone(timezone.utc))


def _clean(value) -> str | None:
    """빈 문자열을 `None` 으로. 모델이 `targetText: ""` 를 낸 것을 측정에서 봤다."""
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def _parse_local(value, *, reference: datetime, warnings: list[str], title: str,
                 field: str) -> datetime | None:
    """모델이 낸 naive 지역 시각을 KST tz-aware 로. 못 읽으면 `None`.

    연도가 없는 형태(`MM-DDTHH:MM`)도 받는다 — 모델이 문서를 그대로 옮기다 연도를
    빠뜨리는 경우가 있다. 그때 기준 연도를 넣고 경고를 남긴다.

    **값이 비어 있는 것은 경고하지 않는다.** 그것은 부르는 쪽이 무엇으로 채웠는지까지
    알고 적어야 할 사실이라, 여기서 한 줄, 저기서 한 줄이 되면 읽는 쪽이 두 문장을
    맞춰 봐야 한다. 읽었으나 형식이 틀린 경우만 여기서 적는다 — 그 원문은 여기밖에
    모른다.
    """
    text = _clean(value)
    if text is None:
        return None

    naive = _parse_naive(text)
    if naive is not None:
        return naive.replace(tzinfo=KST)

    inferred = _parse_without_year(text, reference=reference)
    if inferred is not None:
        warnings.append(f"'{title}'의 연도가 없어 {inferred.year}년으로 보았습니다.")
        return inferred

    warnings.append(f"'{title}'의 {field} 시각을 읽지 못했습니다: {text}")
    return None


def _parse_naive(text: str) -> datetime | None:
    for pattern in ("%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(text, pattern)
        except ValueError:
            continue
    return None


def _parse_without_year(text: str, *, reference: datetime) -> datetime | None:
    """`MM-DDTHH:MM` 에 기준 연도를 넣는다. 너무 과거면 다음 해로 본다."""
    for pattern in ("%m-%dT%H:%M", "%m-%d"):
        try:
            partial = datetime.strptime(text, pattern)
        except ValueError:
            continue
        candidate = partial.replace(year=reference.year, tzinfo=KST)
        if reference - candidate > ROLL_FORWARD_MARGIN:
            candidate = candidate.replace(year=reference.year + 1)
        return candidate
    return None
