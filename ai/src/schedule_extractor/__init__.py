"""일정 문서에서 개별 일정을 뽑는다.

**모델은 판단하고 코드는 계산한다.** 모델에게 맡기는 것은 "이것이 일정인가",
"제목이 무엇인가", "문서가 적은 시각이 무엇인가" 다. 시간대 변환·연도 추론·순서
부여·탈락 판정은 normalize.py 가 한다.

측정 근거 — 모델에게 KST→UTC 변환을 시키면 09:00 KST 를 09:00Z 로 낸다.
코드가 변환하면 맞는다 (설계 §2.1).

이 패키지는 FastAPI 를 모르고 계약 스키마도 모른다. wiki_api 가 계약 모양을 안다.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .provider import JsonCompletionProvider, ProviderError

__all__ = ["ExtractedSchedule", "Extraction", "JsonCompletionProvider",
           "ProviderError", "extract_schedules"]


@dataclass(frozen=True)
class ExtractedSchedule:
    order: int
    title: str
    content: str | None      # 없으면 warning. FR-SCH-001 이 추출을 요구한다
    target_text: str | None  # 같음
    location: str | None     # 요구 대상 아니다. 없어도 조용히 넘어간다
    start_at: datetime       # tz-aware UTC
    end_at: datetime


@dataclass(frozen=True)
class Extraction:
    schedules: tuple[ExtractedSchedule, ...]
    warnings: tuple[str, ...]
    raw_count: int
    """모델이 낸 일정 건수. 정규화가 살린 건수가 아니다. 예외가 하나 있다 — 문서에
    날짜 근거가 없어 출력을 통째로 버린 경우 0 으로 내린다. 그것은 모델이 발명한
    것이므로 「모델이 뭔가 냈다」는 근거로 쓸 수 없다.

    `schedules` 가 비었을 때 라우터가 이 값으로 `no_schedule` 과 실패를 가른다
    (설계 §3.3). 0 이면 모델이 정말 0건을 낸 것이고, 0 보다 크면 정규화가 전부
    떨어뜨린 것이라 실패다."""


async def extract_schedules(markdown: str, *, now: datetime,
                            provider: JsonCompletionProvider) -> Extraction:
    """일정 추출 1회. 모델 호출 1번 + 정규화 + 날짜 근거 검사.

    now 는 tz-aware UTC 로 받는다. 연도가 없는 날짜의 기준 연도를 정하는 데만 쓴다.
    """
    from .date_probe import has_any_date_evidence
    from .normalize import normalize
    from .prompt import OUTPUT_SCHEMA, build_prompt

    raw = await provider.complete_json(build_prompt(markdown, now=now), OUTPUT_SCHEMA)
    schedules, warnings = normalize(raw, now=now)
    raw_count = len(raw.get("schedules") or [])

    # 문서에 날짜로 읽힐 근거가 하나도 없으면 어떤 일정도 성립할 수 없다 — 나온
    # 것은 모델이 만들어낸 것이다. 실측에서 qwen2.5:7b-instruct 가 날짜 없는
    # 안내문에 {연도}-01-01 짜리 일정을 발명했다. 프롬프트가 금지해도 어긴다.
    # NFR-AI-002 는 근거 없는 생성을 금지한다.
    #
    # `has_date_pattern` 이 아니라 `has_any_date_evidence` 를 쓴다 — 그 함수는
    # 모델의 0건을 검증하는 엄격한 검사라서, 여기(모델이 이미 일정을 낸 경우)에
    # 쓰면 "8월 행사 안내" 와 "12일 워크숍" 처럼 월·일이 떨어져 적힌 문서에서
    # 모델이 문맥으로 정당하게 뽑은 진짜 일정까지 조용히 버린다. 두 검사는
    # 임계가 정반대다(date_probe.py 참고).
    if schedules and not has_any_date_evidence(markdown):
        warnings = warnings + (
            f"문서에 날짜가 없는데 모델이 일정 {len(schedules)}건을 냈습니다. 버렸습니다.",)
        schedules = ()
        raw_count = 0

    return Extraction(schedules=schedules, warnings=warnings, raw_count=raw_count)
