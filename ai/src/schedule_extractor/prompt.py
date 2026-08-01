"""모델에게 줄 프롬프트와 출력 스키마.

**모델에게 시간 산술을 시키지 않는다.** 측정에서 `09:00` KST 를 `09:00Z` 로 냈다
(설계 §2.1). 모델은 문서에 적힌 지역 시각을 `YYYY-MM-DDTHH:MM` 으로 옮기고,
UTC 변환은 `normalize.py` 가 한다.

**출력 스키마에 `status` 를 두지 않는다.** 측정에서 모델이 일정 2건을 내면서
`no_schedule` 이라 답했다. 코드가 건수로 정하면 그 모순이 불가능해진다 (§6.3).
"""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")

OUTPUT_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "schedules": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "content": {"type": "string"},
                    "targetText": {"type": "string"},
                    "location": {"type": "string"},
                    "startLocal": {"type": "string"},
                    "endLocal": {"type": "string"},
                    "allDay": {"type": "boolean"},
                },
                "required": ["title", "startLocal", "endLocal", "allDay"],
            },
        },
        "warnings": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["schedules", "warnings"],
}

_TEMPLATE = """사내 일정 문서에서 개별 일정을 모두 추출한다.

시각 규칙 — 계산하지 않는다:
- `startLocal`·`endLocal` 은 문서에 적힌 시각을 그대로 `YYYY-MM-DDTHH:MM` 으로 쓴다.
  시간대 변환을 하지 않는다.
- 기준 연도는 {year}년이다. 연도가 없으면 {year}을 쓴다.
- 시각이 없고 날짜만 있으면 `allDay` 를 true 로 하고 `startLocal` 은 `그날T00:00`,
  `endLocal` 은 `그날T23:59` 로 쓴다.
- 시작 시각은 있고 종료 시각이 없으면 문맥으로 추정하고 warnings 에 남긴다.

추출 규칙:
- 날짜가 있는 항목은 전부 일정이다. 급여 지급일, 신청 마감일도 일정이다.
- 문의처·연락처처럼 날짜가 없는 것은 일정이 아니다.
- `title` 은 그 일정을 가리키는 이름이다.
- `content` 는 일정의 설명, `targetText` 는 문서가 적은 대상, `location` 은 장소다.
  문서에 적혀 있으면 반드시 옮긴다. 없으면 비운다.
- 불명확한 날짜·시각·대상은 warnings 에 한국어로 남긴다.

문서:
{document}
"""


def build_prompt(markdown: str, *, now: datetime) -> str:
    """기준 연도를 KST 로 본다.

    UTC 자정 전후 9시간은 두 시간대의 날짜가 다르고, 12월 31일 밤에는 연도까지
    다르다. 문서를 쓴 사람의 연도가 기준이다 (설계 §3.5).
    """
    return _TEMPLATE.format(year=now.astimezone(KST).year, document=markdown)
