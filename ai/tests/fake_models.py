"""대사를 미리 정해 둔 가짜 모델. **테스트 전용이고 돈이 들지 않는다.**

`GenericFakeChatModel` 은 대사를 순서대로 내주지만 `bind_tools` 가 `NotImplementedError`
라서 에이전트 그래프에 그대로 끼울 수 없다 (2026-07-31 실측). 도구를 무는 대신 자기를
돌려주면 루프·도구 실행·응답 조립은 전부 진짜로 돌고 **모델 판단만 가짜**가 된다.
"""

from __future__ import annotations

from langchain_core.language_models.fake_chat_models import GenericFakeChatModel


class ScriptedChatModel(GenericFakeChatModel):
    """정해진 `AIMessage` 를 순서대로 낸다. 도구 결합은 무시한다."""

    def bind_tools(self, tools, **kwargs):  # noqa: ARG002 - 가짜라 무는 것이 없다
        return self
