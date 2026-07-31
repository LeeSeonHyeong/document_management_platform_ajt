"""챗봇 — 에이전트 하나가 조회하고 답한다 (`POST /internal/v1/answers`).

**출처는 모델의 신고와 읽은 기록의 교집합이다.** 모델이 「이걸 썼다」고 적고, 우리가 도구
기록으로 검사한다 — 안 읽은 것을 신고해도 통과하지 못하고(지어내기 불가), 읽었지만 답에
쓰지 않은 것도 섞이지 않는다. 이전 구현은 모델이 낸 ID 를 **요청에 실려 온** 집합으로만
걸러서 「안 읽은 자료를 출처로 신고하는 것」을 막지 못했다.

**질문 유형은 모델이 질문 맥락으로 판단한다** (FR-QNA-002). 읽은 자료의 종류로 정하면 일정
질문을 위키 공지로 답했을 때 유형이 뒤집힌다.

**근거를 못 찾은 것은 실패가 아니다** (FR-QNA-007). 찾아봤는데 없으면 빈 출처로 안내하고,
도구를 아예 부르지 않은 실행만 실패로 낸다 — 그것은 「모른다」가 아니라 고장이다.

**MCP 도 작업 공간도 쓰지 않는다.** 쓰기가 없기 때문이다 — 그래서 지연 적재 fan-out
(S15P11B106-151)과 프로세스 밖 권한값 전달(152)이 이 경로에 걸리지 않는다.
"""

from __future__ import annotations

import logging
from datetime import datetime
from zoneinfo import ZoneInfo

from agent_runtime.base import RunResult

from .answer_guide import AnswerReport, chat_guide
from .answer_tools import (
    ChatQueryClient,
    QueryFailed,
    ReadLedger,
    build_schedule_tools,
    build_wiki_tools,
)
from .errors import InternalError
from .schemas import AnswerRequest, AnswerResponse, AnswerSource

logger = logging.getLogger("llmwiki.chat")

# 상한. 둘 다 근거 없는 시작점이다 (설계 §6.6). 턴 수가 주 장치, 시간은 안전장치.
# 8턴·60초에서 내렸다 — 채팅에서 1분 침묵은 사용자가 창을 닫고, 그 값에서는 시간이 먼저
# 걸려 턴 상한이 무의미해진다. 2026-07-31 실측(질문 1건)은 10.7초·도구 4번이었다.
MAX_TURNS = 5
TIMEOUT_SECONDS = 25

# 이전 대화 상한. 설계 문서에 적혀 있었으나 구현되지 않았던 값이다.
MAX_HISTORY_ITEMS = 12
MAX_HISTORY_CHARS = 4_000

# 오류 이름. **이름만 읽고 무슨 일이 있었는지 알 수 있게 짓는다** — 계약이 허용한 상태는
# 400·401·500 셋뿐이라 이름이 상태를 대신 설명해야 한다. 계약에 없는 이름이므로 MR 본문에
# 협의 항목으로 적는다 (`ai/CLAUDE.md`).
VALIDATION_CODE = "INVALID_ANSWER_REQUEST"
NO_SOURCE_CODE = "NO_WIKI_OR_SCHEDULE_WAS_READ"
WIKI_QUERY_CODE = "WIKI_QUERY_FAILED"
SCHEDULE_QUERY_CODE = "SCHEDULE_QUERY_FAILED"
TURN_LIMIT_CODE = "AGENT_TURN_LIMIT_REACHED"
TIMEOUT_CODE = "AGENT_TIMED_OUT"
MODEL_CODE = "MODEL_CALL_FAILED"
EMPTY_ANSWER_CODE = "ANSWER_WAS_EMPTY"


def trim_history(messages: list[dict], *,
                 max_items: int = MAX_HISTORY_ITEMS,
                 max_chars: int = MAX_HISTORY_CHARS) -> list[dict]:
    """이전 대화를 최근 것부터 남긴다. **요청을 거절하지 않는다.**

    대화가 길어지면 상한을 넘는 것이 당연하다 — 백엔드 잘못이 아니므로 조용히 자르고
    최근 것으로 답한다. 에이전트는 여기에 도구 결과까지 쌓으므로 상한이 필요하다.

    하나만 남았는데 그것이 글자 상한을 넘으면 버리지 않는다. 버리면 대화가 통째로
    사라져서 대명사("그거")를 풀 수 없다.
    """
    kept: list[dict] = []
    total = 0
    for message in reversed(messages[-max_items:]):
        size = len(str(message.get("content") or ""))
        if kept and total + size > max_chars:
            break
        kept.append(message)
        total += size
    kept.reverse()
    return kept


def build_response(run: RunResult, ledger: ReadLedger) -> AnswerResponse:
    """모델의 신고를 읽은 기록으로 검사해 응답을 만든다.

    **도구를 아예 부르지 않은 실행만 실패다.** 찾아봤지만 근거가 없었던 것은 정상 응답이다
    (FR-QNA-007) — 실패로 내면 사용자에게 「처리 중 문제가 생겼습니다」가 나가고, 지침의
    「모르면 모른다고 한다」와 정면으로 부딪친다.
    """
    if not run.tool_calls:
        raise InternalError(
            NO_SOURCE_CODE,
            "위키도 일정도 조회하지 않아 근거가 없습니다.", status=500)

    report = run.structured or {}
    answer = (str(report.get("answer") or "") or (run.text or "")).strip()
    if not answer:
        raise InternalError(EMPTY_ANSWER_CODE, "모델이 빈 답변을 냈습니다.", status=500)

    # 신고가 없으면 읽은 것 전부로 떨어진다 — 출처를 0개로 만드는 것보다 과다 포함이 낫다.
    if report:
        used_wikis = [str(i) for i in report.get("usedWikiIds") or []]
        used_schedules = [str(i) for i in report.get("usedScheduleIds") or []]
    else:
        used_wikis = [wiki_id for wiki_id, _ in ledger.wikis]
        used_schedules = [schedule_id for schedule_id, _ in ledger.schedules]

    # 읽은 기록이 화이트리스트다. 안 읽은 것을 신고해도 여기서 빠진다.
    sources = [AnswerSource(type="wiki", wikiId=wiki_id,
                            title=ledger.title_of_wiki(wiki_id))
               for wiki_id in used_wikis if ledger.title_of_wiki(wiki_id)]
    sources += [AnswerSource(type="schedule", scheduleId=schedule_id,
                             title=ledger.title_of_schedule(schedule_id))
                for schedule_id in used_schedules
                if ledger.title_of_schedule(schedule_id)]

    kind = report.get("questionType")
    if kind not in ("wiki", "schedule", "mixed"):
        # 신고가 없거나 값이 이상하면 읽은 것으로 채운다. 이때만 결과로 정한다.
        kind = ("mixed" if ledger.wikis and ledger.schedules
                else "schedule" if ledger.schedules else "wiki")
    return AnswerResponse(answer=answer, sources=sources, questionType=kind)


async def _run(runtime, guide: str, question: str, *, tools: list) -> RunResult:
    """에이전트를 돌린다. **비동기 경로가 있으면 그것을 쓴다.**

    sync 런타임을 `asyncio.to_thread` 로 띄우면 그 안의 `asyncio.run` 이 요청마다 루프를
    새로 만들고 닫는다. 모델 클라이언트의 연결 풀은 자기를 만든 루프에 묶여 있어서, **두
    번째 요청부터 `Event loop is closed`** 가 `APIConnectionError` 로 감싸여 올라온다 —
    Spring 실연동에서 서버 기동 후 첫 질문만 답했다 (2026-07-31).

    그래서 서버 루프에서 그대로 await 한다. sync 만 있는 런타임(테스트의 가짜)은 스레드로
    돌린다 — 그쪽은 모델도 HTTP 도 없어 루프에 묶일 것이 없다.
    """
    import asyncio

    runner = getattr(runtime, "arun_with_tools", None)
    if runner is not None:
        return await runner(guide, question, tools=tools, max_turns=MAX_TURNS,
                            timeout=TIMEOUT_SECONDS, response_format=AnswerReport)
    return await asyncio.to_thread(
        runtime.run_with_tools, guide, question, tools=tools,
        max_turns=MAX_TURNS, timeout=TIMEOUT_SECONDS,
        response_format=AnswerReport)


async def generate_answer(runtime, payload: AnswerRequest, *,
                          backend_base_url: str, api_key: str,
                          request_id: str = "") -> AnswerResponse:
    """에이전트를 한 번 돌리고 응답을 조립한다.

    `asyncio.to_thread` 로 띄우는 이유는 런타임이 sync 이기 때문이다. 그 스레드는 취소할
    수 없으므로 **실제 상한은 런타임에 넘긴 `timeout`** 이다 (`completion.py` 와 같은 이유).
    """
    import asyncio

    if not backend_base_url:
        raise InternalError(
            VALIDATION_CODE,
            "백엔드 주소가 설정되지 않아 위키·일정을 조회할 수 없습니다.", status=500)

    capabilities = {entry.scopeKey: entry.wikiCapability
                    for entry in payload.wikiIndexes if entry.wikiCapability}
    ledger = ReadLedger()
    client = ChatQueryClient(backend_base_url, api_key=api_key,
                             question_id=payload.questionId,
                             capabilities=capabilities)
    try:
        tools = [*build_wiki_tools(client, ledger),
                 *build_schedule_tools(client, ledger)]
        guide = chat_guide(
            trim_history([m.model_dump() for m in payload.conversationMessages]),
            [entry.model_dump() for entry in payload.wikiIndexes],
            # 사용자 감각과 맞춰야 한다. 모델은 오늘을 모른다.
            today=datetime.now(ZoneInfo("Asia/Seoul")).date(),
        )
        try:
            run = await _run(runtime, guide, payload.question, tools=tools)
        except TimeoutError as timed_out:
            raise InternalError(TIMEOUT_CODE,
                                "제한 시간 안에 답변을 만들지 못했습니다.",
                                status=500) from timed_out
        except QueryFailed as failed:
            raise InternalError(WIKI_QUERY_CODE, "위키·일정 조회가 실패했습니다.",
                                status=500) from failed
        except NotImplementedError:
            raise
        except Exception as broke:                     # noqa: BLE001
            # 계약 응답에는 이름만 나간다. **원인은 서버 로그에만 남긴다** — 실연동에서
            # `MODEL_CALL_FAILED` 만 보이고 무엇이 터졌는지 알 수 없었다 (2026-07-31).
            logger.exception("모델 호출 실패 — requestId=%s", request_id)
            raise InternalError(MODEL_CODE, "모델 호출이 실패했습니다.",
                                status=500) from broke
        if run.error == "turn_limit":
            # 어떤 도구를 몇 번 불렀는지가 상한 조정의 근거다. 이것이 없으면 「걸렸다」만
            # 남는다.
            logger.warning("턴 상한 도달 — requestId=%s 도구=%s", request_id,
                           run.tool_calls)
            if not run.tool_calls:
                # **도구를 한 번도 못 불렀으면 상한이 아니라 고장이다.** 모델 호출이 매번
                # 실패해도 호출 수는 올라가므로 상한 이름이 먼저 나오고, 그러면 「상한을
                # 늘려야 하나」를 들여다보게 된다 — 실연동에서 그 이름 때문에 루프 결함을
                # 네트워크 문제로 오진했다 (2026-07-31).
                raise InternalError(MODEL_CODE,
                                    "모델 호출이 실패했습니다.", status=500)
            # 반쯤 읽고 만든 답변은 근거가 빠져 있다. 이름을 구분해 내보낸다 — 이것이
            # `MODEL_CALL_FAILED` 로 뭉개지면 상한을 조정할 근거를 못 얻는다.
            raise InternalError(TURN_LIMIT_CODE,
                                "정해진 횟수 안에 답변을 만들지 못했습니다.", status=500)
        return build_response(run, ledger)
    finally:
        client.close()
