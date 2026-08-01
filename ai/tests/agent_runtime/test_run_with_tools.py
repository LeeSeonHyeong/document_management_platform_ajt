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


async def test_an_async_tool_is_wrapped_as_a_coroutine():
    """위키 편집 도구는 저장소를 만지므로 async 다 (S15P11B106-152).

    sync 로 감싸면 `call` 이 코루틴 객체를 돌려주고 모델은 그것을 문자열로 찍는다 —
    도구가 조용히 아무 일도 안 한 것처럼 보인다.
    """
    from agent_runtime.deep_agents import langchain_tools

    async def call(text: str) -> str:
        return f"들었다: {text}"

    tool = AgentTool(name="echo", description="", call=call,
                     input_schema={"type": "object",
                                   "properties": {"text": {"type": "string"}},
                                   "required": ["text"]})

    wrapped = langchain_tools([tool])[0]

    assert await wrapped.ainvoke({"text": "안녕"}) == "들었다: 안녕"


async def test_an_async_tool_is_counted_too():
    """턴 상한 예외로 끝난 실행도 호출 수를 남겨야 한다 — 세는 곳이 여기다."""
    from agent_runtime.deep_agents import langchain_tools

    async def call() -> str:
        return "ok"

    counter: dict = {}
    tool = AgentTool(name="ping", description="", call=call,
                     input_schema={"type": "object", "properties": {}})

    await langchain_tools([tool], counter)[0].ainvoke({})

    assert counter == {"ping": 1}


# ---- MCP 없이 도는 위키 편집 실행 (S15P11B106-152) --------------------------


async def test_arun_edits_the_vault_without_spawning_a_server(tmp_path):
    """자동 실행이 하위 프로세스 없이 위키를 고친다.

    창구 모드(`FederatedVaultFS`)가 열리는 조건이다 — 열람 허가값과 중단 신호가
    프로세스 경계를 못 넘어서 지금까지 접수 시점에 거절돼 있었다 (설계 §3.7).
    """
    from langchain_core.messages import AIMessage

    from agent_runtime.deep_agents import DeepAgentsRuntime
    from tests.fake_models import ScriptedChatModel
    from wiki_mcp.vaultfs import LocalVaultFS

    scope_id = await LocalVaultFS.open(tmp_path, "D1", "job-1")
    fs = LocalVaultFS("D1", "job-1")
    try:
        writing = AIMessage(content="", tool_calls=[{
            "name": "create", "id": "1", "type": "tool_call",
            "args": {"scope": "D1", "title": "재택근무 규정", "category": "근무",
                     "tags": ["근태"], "content": "재택근무는 주 2일까지 가능하다.\n"}}])
        model = ScriptedChatModel(messages=iter([writing, AIMessage(content="끝")]))
        runtime = DeepAgentsRuntime(model="openai:gpt-4o-mini", chat_model=model)

        run = await runtime.arun("문서를 위키로 만들어라", fs=fs, scope_id=scope_id,
                                 root=tmp_path, scope_key="D1", job_id="job-1")

        assert run.error is None
        assert run.tool_calls == {"create": 1}
        changes = await fs.pending_changes(scope_id)
        assert [c["type"] for c in changes] == ["create"]
    finally:
        await LocalVaultFS.close()


async def test_arun_reports_the_time_limit_as_its_own_failure(tmp_path):
    """상한 초과는 예외가 아니라 `error` 문장이다 — `run` 과 같은 규약."""
    import asyncio

    from langchain_core.messages import AIMessage

    from agent_runtime.deep_agents import DeepAgentsRuntime
    from tests.fake_models import ScriptedChatModel
    from wiki_mcp.vaultfs import LocalVaultFS

    scope_id = await LocalVaultFS.open(tmp_path, "D1", "job-1")
    fs = LocalVaultFS("D1", "job-1")

    class SlowModel(ScriptedChatModel):
        async def _agenerate(self, *args, **kwargs):
            await asyncio.sleep(5)
            raise AssertionError("여기까지 오면 안 된다")

    try:
        model = SlowModel(messages=iter([AIMessage(content="끝")]))
        runtime = DeepAgentsRuntime(model="openai:gpt-4o-mini", chat_model=model)

        run = await runtime.arun("문서를 위키로 만들어라", fs=fs, scope_id=scope_id,
                                 root=tmp_path, scope_key="D1", job_id="job-1",
                                 timeout=1)

        assert run.error and "1초" in run.error
    finally:
        await LocalVaultFS.close()
