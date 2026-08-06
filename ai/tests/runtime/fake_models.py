"""각본대로 도구를 부르는 가짜 모델.

## 왜 있는가

에이전트 결함의 상당수가 **미들웨어끼리 충돌**해서 난다 — 단위 테스트는 함수만 보고,
하네스는 검색 경로가 달라 재현이 안 되고, 실기동은 돈이 들고 한 번에 결함 하나만
드러낸다. 그 사이가 비어 있었다.

가짜 모델은 **진짜 그래프·진짜 미들웨어·진짜 MCP 도구**를 그대로 태우고 모델만 바꾼다.
LLM 호출이 0 이므로 고칠 때마다 전 구간을 무료로 돌릴 수 있다.

실제로 이 방식이 `recursion_limit` 결함을 잡았다 (`test_turn_limit.py`) — 첫 수정값이
여전히 모자랐던 것이 여기서 드러났다.

## 무엇을 검증할 수 없는가

**모델의 판단은 못 잰다.** 「지시문을 읽고 표 양식으로 보고하는가」·「검색 결과를 보고
`create` 로 갈 줄 아는가」는 각본이 정하는 것이라 자기가 쓴 답을 자기가 채점하는 꼴이다.
그것은 실기동의 몫이다. 여기서 잡는 것은 **배선**이다 — 도구가 있는가, 압축 뒤에 복구
경로가 열려 있는가, 상한이 이름을 갖는가.
"""

from __future__ import annotations

import re

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult

_HISTORY_PATH = re.compile(r"(/conversation_history/[^\s`\"']+\.md)")


class ScriptedModel(BaseChatModel):
    """`script` 의 각 항목을 순서대로 낸다.

    항목은 `("tool", 이름, 인자dict)` 이거나 `("text", 문자열)` 이다. 각본이 끝나면
    빈 텍스트를 내 그래프를 종료시킨다.
    """

    script: list = []
    calls: int = 0
    seen_history_path: str | None = None
    padding: str = ""
    # 요약 요청(압축이 부르는 것)을 받은 횟수. 에이전트 호출과 **분리해 센다** — 요약
    # 요청이 각본 항목을 소비하면 압축 횟수에 따라 각본이 어긋나 테스트가 비결정적이 된다.
    summary_requests: int = 0

    @property
    def _llm_type(self) -> str:
        return "scripted"

    def bind_tools(self, tools, **kwargs):
        return self

    def _next(self):
        if self.calls < len(self.script):
            return self.script[self.calls]
        return ("text", "")

    def _generate(self, messages, stop=None,
                  run_manager: CallbackManagerForLLMRun | None = None,
                  **kwargs) -> ChatResult:
        # 요약 메시지가 알려주는 이력 경로를 기억한다. 진짜 모델이 그 안내를 읽고
        # 그대로 부르는 것을 흉내내는 것이고, 각본이 `"$history"` 로 참조한다.
        for m in messages:
            found = _HISTORY_PATH.search(str(getattr(m, "content", "")))
            if found:
                self.seen_history_path = found.group(1)

        # 압축의 요약 요청은 각본 밖에서 처리한다. 요약 모델도 이 가짜가 맡으므로
        # (`_chat_model_override` 규약) 구분하지 않으면 각본 항목을 소비해 압축 횟수에
        # 따라 테스트가 흔들린다. 우리 프롬프트의 고정 문자열(`wiki_work_state`)로
        # 식별한다 — 이 카운터가 곧 「작업 상태 보존 지시가 요약 모델에 전달됐다」의 증거다.
        if any("wiki_work_state" in str(getattr(m, "content", "")) for m in messages):
            self.summary_requests += 1
            return ChatResult(generations=[ChatGeneration(message=AIMessage(
                content=("## 진행 상태\n원본문서 반영 작업 중이다. 페이지를 만들고 "
                         "lint 를 통과했다.\n남은 일 없음 — 최종 보고만 남았다")))])

        item = self._next()
        self.calls += 1
        if item[0] == "text":
            return ChatResult(generations=[
                ChatGeneration(message=AIMessage(content=item[1] or "끝"))])

        _, name, args = item
        args = {k: (self.seen_history_path or "")
                if v == "$history" else v for k, v in args.items()}
        return ChatResult(generations=[ChatGeneration(message=AIMessage(
            # 압축을 유발하려면 이력이 커져야 한다. 도구 결과만으로 부족할 때 쓴다.
            content=self.padding,
            tool_calls=[{"name": name, "args": args, "id": f"c{self.calls}"}],
        ))])
