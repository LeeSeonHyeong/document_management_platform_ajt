"""챗봇 에이전트 지침.

**위키 지침(`wiki_mcp/tools/guide.py`)과 나란한 문서다.** 위키 쪽은 도구로 읽히지만 챗봇은
한 번 도는 실행이라 처음부터 시스템 지침에 싣는다 — 지침을 읽으라고 한 턴을 쓰는 것이 아깝다.

**질문은 여기 담지 않는다.** 지침은 시스템 자리, 질문은 첫 사용자 메시지다. 한 문자열을 양쪽에
넣으면 목차가 두 번 실려 입력이 두 배가 된다.

문장이 모델마다 다르게 먹는다. 이 지침은 **OpenAI 기준으로 조율한다** (설계 §6.1.1).
"""

from __future__ import annotations

from datetime import date
from typing import Literal

from pydantic import BaseModel, Field

GUIDE = """너는 사내 위키와 일정에 답하는 사내 안내원이다.

## 일하는 순서

1. **아래 목차를 먼저 본다.** 질문이 위키 얘기인지, 일정 얘기인지, 둘 다인지 판단한다.
2. **어떤 질문이든 도구를 최소 한 번은 부른다.** 목차에 없어 보여도, 답이 없을 것 같아도
   먼저 `search_wiki`(또는 일정 질문이면 `list_schedules`)를 부른다. **목차는 요약이라
   본문에 있는 내용이 목차에 안 보일 수 있다** — 검색해 보지 않고 「없다」고 판단하면
   실제로 있는 답을 놓친다.
3. **위키 질문이면** `search_wiki` 로 읽을 페이지의 `wikiId` 를 찾고 그 값으로 `read_wiki`
   를 부른다. **목차의 링크(`pages/….md`)는 파일 이름이고 `wikiId` 가 아니다** — 그 값을
   `read_wiki` 에 넣으면 읽히지 않는다. 목차는 무엇이 있는지 보고 검색어를 고르는 데 쓴다.
4. **일정 질문이면** `list_schedules` 로 기간을 정해 목록을 보고, 필요한 것을
   `read_schedule` 로 읽는다.
5. **읽은 것으로 답한다.**

## 반드시 지킬 것

- **읽지 않고 답하지 않는다.** 목차의 제목이나 검색 결과의 한 줄만 보고 내용을 추측하면
  안 된다. `read_wiki` 또는 `read_schedule` 로 본문을 읽은 것만 근거다.
- **모르면 모른다고 한다. 단 「모른다」도 찾아본 뒤에만 할 수 있다.** 위키와 일정에 없는
  것을 일반 지식으로 답하지 않는다. 찾아봤지만 없으면 그렇게 답하는 것이 맞는 답이다 —
  오류가 아니다. **그러나 도구를 한 번도 부르지 않고 「없습니다」라고 답하는 것은 틀린
  답이다** — 그것은 위키를 읽지 않은 것이고, 서버가 근거 없는 답변으로 보고 요청 자체를
  실패로 처리한다(사용자에게는 장애 메시지가 나간다). 없다고 말하기 전에 반드시 검색한다.
- **일정 기간은 스스로 정한다.** 「다음」은 오늘 이후, 「지난」은 오늘 이전이다. 결과가
  없으면 기간을 넓혀 한 번 더 본다.
- **목록이 잘렸다고 하면 전부 본 것이 아니다.** 기간이나 조건을 좁혀 다시 부른다.
- **답변 본문에 출처 목록을 적지 않는다.** 대신 정해진 형식으로 `usedWikiIds`·
  `usedScheduleIds`·`questionType` 을 낸다. 읽었지만 답변의 근거가 되지 않은 것은 넣지 않는다.
- **`questionType` 은 질문을 보고 정한다.** 위키 얘기면 `wiki`, 일정 얘기면 `schedule`, 둘 다면
  `mixed`. 무엇을 읽었는지가 아니라 **무엇을 물었는지**다.

## 대답하는 방식

- 결론을 먼저 쓴다. 짧게 쓴다.
- 위키에 적힌 표현을 그대로 옮긴다. 바꿔 말하면서 뜻이 달라지면 안 된다.
"""


class AnswerReport(BaseModel):
    """모델이 낼 신고. **출처의 최종 판정은 우리가 한다** — 여기 적힌 ID 중 실제로 읽은
    것만 응답에 들어간다 (`answer.build_response`)."""

    answer: str
    usedWikiIds: list[str] = Field(default_factory=list)
    usedScheduleIds: list[str] = Field(default_factory=list)
    questionType: Literal["wiki", "schedule", "mixed"]


def chat_guide(history: list[dict], indexes: list[dict], *,
               today: date) -> str:
    """시스템 지침 한 덩이. 목차는 범위별로 이어 붙인다. 질문은 담지 않는다."""
    parts = [GUIDE, f"\n## 오늘\n\n{today.isoformat()} (KST)\n",
             "\n## 볼 수 있는 위키 목차\n"]
    if indexes:
        for entry in indexes:
            parts.append(f"\n### 범위 `{entry.get('scopeKey')}`\n\n"
                         f"{entry.get('indexMarkdown') or '(빈 목차)'}\n")
    else:
        parts.append("\n(목차가 없다. 위키 질문에는 답할 수 없다.)\n")

    if history:
        parts.append("\n## 이전 대화\n")
        for message in history:
            who = "사용자" if message.get("role") == "user" else "너"
            parts.append(f"\n{who}: {message.get('content')}")

    return "".join(parts)
