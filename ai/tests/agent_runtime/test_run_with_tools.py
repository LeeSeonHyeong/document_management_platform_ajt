"""도구를 직접 붙이는 실행 경로.

모델을 부르지 않는다 — LangChain 조립까지만 보고, 모델 호출은 실기동(Task 10)에서 본다.
"""

import pytest

from agent_runtime.tools import AgentTool


def _tool() -> AgentTool:
    return AgentTool(name="echo", description="받은 말을 돌려준다",
                     input_schema={"type": "object",
                                   "properties": {"text": {"type": "string"}},
                                   "required": ["text"]},
                     call=lambda text: f"들었다: {text}")


def test_claude_code_refuses_with_a_clear_reason():
    from agent_runtime.claude_code import ClaudeCodeRuntime

    runtime = ClaudeCodeRuntime(model="claude-opus-4-6")

    with pytest.raises(NotImplementedError) as raised:
        runtime.run_with_tools("지침", "질문", tools=[_tool()], max_turns=5, timeout=25)

    assert "deepagents" in str(raised.value)


def test_turn_limit_is_reported_as_itself_not_as_a_model_failure():
    """`recursion_limit` 으로만 걸면 GraphRecursionError 가 일반 예외로 잡혀
    `MODEL_CALL_FAILED` 로 나간다 — `AGENT_TURN_LIMIT_REACHED` 를 계약에 올려놓고 한 번도
    내지 않게 된다. 가짜 모델로 상한을 넘겨 이름이 구분되는지 고정한다."""
    from langchain_core.messages import AIMessage

    from agent_runtime.deep_agents import DeepAgentsRuntime
    from tests.fake_models import ScriptedChatModel

    calling = AIMessage(content="", tool_calls=[
        {"name": "echo", "args": {"text": "또"}, "id": "1", "type": "tool_call"}])
    # 끝내지 않고 계속 도구만 부른다.
    model = ScriptedChatModel(messages=iter([calling] * 20))
    runtime = DeepAgentsRuntime(model="openai:gpt-4o-mini", chat_model=model)

    run = runtime.run_with_tools("지침", "질문", tools=[_tool()], max_turns=3, timeout=25)

    assert run.error == "turn_limit"


def test_deepagents_wraps_agent_tools_for_langchain():
    """`AgentTool` 이 LangChain 도구로 감싸지고 이름·스키마가 보존되는지."""
    from agent_runtime.deep_agents import langchain_tools

    wrapped = langchain_tools([_tool()])

    assert [t.name for t in wrapped] == ["echo"]
    assert wrapped[0].invoke({"text": "안녕"}) == "들었다: 안녕"
