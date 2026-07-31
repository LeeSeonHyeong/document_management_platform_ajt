"""챗봇 도구 — Spring 조회와 읽은 것 장부.

**장부는 출처의 화이트리스트다.** 모델이 「이걸 썼다」고 신고한 것을 이 기록과 대조해 걸러낸다
(`answer.build_response`). 그래서 안 읽은 페이지를 출처로 신고해도 통과하지 못하고, 읽었지만
답에 쓰지 않은 페이지도 섞이지 않는다.

**질문 유형은 여기서 정하지 않는다.** 모델이 질문 맥락을 보고 판단한다 (FR-QNA-002).
"""

from __future__ import annotations

import json
import logging

import httpx

from agent_runtime.tools import AgentTool

# 조회 실패는 **모델에게 문장으로** 돌려준다 — 그래야 다른 범위를 볼 수 있다. 그런데 그러면
# 사람 쪽에는 아무 기록이 남지 않아, 실연동에서 「턴 상한에 걸렸다」만 보이고 왜인지 알 수
# 없었다 (2026-07-31). 실패는 여기서 로그로도 남긴다. **허가값은 절대 찍지 않는다.**
logger = logging.getLogger("llmwiki.chat")

# 위키 검색 상한. 에이전트가 실제로 보는 것은 이 행들이므로 문맥이 여기서 부푼다.
MAX_WIKI_SEARCH_ROWS = 20
QUERY_TIMEOUT_SECONDS = 15


class QueryFailed(RuntimeError):
    """백엔드 조회가 실패했다. 호출자가 오류 코드로 바꾼다."""


class ReadLedger:
    """한 요청 동안 무엇을 읽었는지. 순서를 지키고 중복을 지운다."""

    def __init__(self) -> None:
        self._wikis: dict[str, str] = {}
        self._schedules: dict[str, str] = {}

    def note_wiki(self, wiki_id: str, title: str) -> None:
        self._wikis.setdefault(str(wiki_id), title)

    def note_schedule(self, schedule_id: str, title: str) -> None:
        self._schedules.setdefault(str(schedule_id), title)

    @property
    def wikis(self) -> list[tuple[str, str]]:
        return list(self._wikis.items())

    @property
    def schedules(self) -> list[tuple[str, str]]:
        return list(self._schedules.items())

    def is_empty(self) -> bool:
        return not self._wikis and not self._schedules

    def title_of_wiki(self, wiki_id: str) -> str | None:
        return self._wikis.get(str(wiki_id))

    def title_of_schedule(self, schedule_id: str) -> str | None:
        return self._schedules.get(str(schedule_id))


class ChatQueryClient:
    """챗봇 전용 조회 클라이언트. **위키 편집용 클라이언트를 쓰지 않는다** —
    참조 그래프 재동기화와 범위 버전 대조가 딸려오고, 읽기에는 둘 다 필요 없다.

    `capabilities` 는 범위 → 허가값이다. 요청에 실려 온 목차 행마다 하나씩 온다.
    **허가값이 없는 범위는 부르지 않는다** — 백엔드가 거절할 것을 미리 막아 왕복을 아낀다.
    """

    def __init__(self, base_url: str, *, api_key: str, question_id: str,
                 capabilities: dict[str, str], transport=None) -> None:
        self.question_id = question_id
        self._capabilities = dict(capabilities)
        self._http = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"X-Internal-API-Key": api_key},
            timeout=QUERY_TIMEOUT_SECONDS,
            transport=transport,
        )

    def close(self) -> None:
        self._http.close()

    def capability_for(self, scope_key: str) -> str | None:
        return self._capabilities.get(scope_key)

    def get(self, path: str, *, scope_key: str | None = None,
            params: dict | None = None) -> dict:
        headers = {}
        if scope_key is not None:
            capability = self._capabilities.get(scope_key)
            if not capability:
                raise QueryFailed(f"허가값이 없는 범위입니다: {scope_key}")
            headers["X-Wiki-Capability"] = capability
        response = self._http.get(path, params=params or {}, headers=headers)
        if response.status_code >= 400:
            # 본문을 그대로 싣지 않는다 — 허가값이 되돌아올 여지를 남기지 않는다.
            raise QueryFailed(f"조회 실패 {response.status_code} — {path}")
        return response.json()


def _rows_text(rows: list[dict], keys: tuple[str, ...], limit: int) -> str:
    shown = rows[:limit]
    lines = [json.dumps({k: row.get(k) for k in keys}, ensure_ascii=False)
             for row in shown]
    if len(rows) > limit:
        lines.append(f"({len(rows) - limit}건 더 있습니다. 조건을 좁혀 다시 부르십시오)")
    return "\n".join(lines) if lines else "결과가 없습니다."


def build_wiki_tools(client: ChatQueryClient, ledger: ReadLedger) -> list[AgentTool]:
    scope_schema = {"type": "string", "description": "위키 범위 (목차에 실려 온 scopeKey)"}

    def search_wiki(scopeKey: str, query: str) -> str:
        try:
            body = client.get("/internal/v1/wiki-search", scope_key=scopeKey,
                              params={"scopeKey": scopeKey, "query": query,
                                      "limit": MAX_WIKI_SEARCH_ROWS})
        except QueryFailed as failed:
            logger.warning("search_wiki 실패 — scope=%s: %s", scopeKey, failed)
            return f"볼 수 없는 범위이거나 조회에 실패했습니다: {failed}"
        return _rows_text(body.get("items") or [], ("wikiId", "title", "snippet"),
                          MAX_WIKI_SEARCH_ROWS)

    def read_wiki(scopeKey: str, wikiId: str) -> str:
        try:
            body = client.get(f"/internal/v1/wikis/{wikiId}/content",
                              scope_key=scopeKey, params={"scopeKey": scopeKey})
        except QueryFailed as failed:
            logger.warning("read_wiki 실패 — scope=%s wikiId=%s: %s",
                           scopeKey, wikiId, failed)
            # 실측에서 모델이 목차 링크의 파일 이름(`pages/eddb3cec8f14.md`)을 wikiId 로
            # 넣었다 (2026-07-31). 그때 「조회 실패」만 돌려주면 같은 값으로 다시 부르며
            # 턴을 태운다 — 무엇을 넣어야 하는지 문장으로 알려 준다.
            return (f"읽지 못했습니다: {failed}. wikiId 가 목차 링크의 파일 이름일 수 "
                    f"있습니다 — search_wiki 로 wikiId 를 확인해 다시 부르십시오.")
        title = str(body.get("title") or wikiId)
        ledger.note_wiki(wikiId, title)
        return f"# {title}\n\n{body.get('contentMarkdown') or ''}"

    def read_wiki_index(scopeKey: str) -> str:
        try:
            body = client.get(f"/internal/v1/wiki-spaces/{scopeKey}/index",
                              scope_key=scopeKey)
        except QueryFailed as failed:
            logger.warning("read_wiki_index 실패 — scope=%s: %s", scopeKey, failed)
            return f"볼 수 없는 범위이거나 조회에 실패했습니다: {failed}"
        return str(body.get("indexMarkdown") or "목차가 비어 있습니다.")

    return [
        AgentTool(
            name="search_wiki",
            description="위키 본문을 검색한다. 목차에서 찾지 못했을 때 쓴다. "
                        "결과는 제목과 일부 문장뿐이므로 답하기 전에 read_wiki 로 본문을 "
                        "읽어야 한다.",
            input_schema={"type": "object",
                          "properties": {"scopeKey": scope_schema,
                                         "query": {"type": "string",
                                                   "description": "찾을 말"}},
                          "required": ["scopeKey", "query"]},
            call=search_wiki,
        ),
        AgentTool(
            name="read_wiki",
            description="위키 한 장의 본문을 읽는다. 답변의 근거가 되는 유일한 방법이다.",
            input_schema={"type": "object",
                          "properties": {"scopeKey": scope_schema,
                                         "wikiId": {"type": "string"}},
                          "required": ["scopeKey", "wikiId"]},
            call=read_wiki,
        ),
        AgentTool(
            name="read_wiki_index",
            description="범위 하나의 목차를 다시 읽는다. 요청에 실려 온 목차로 충분하면 "
                        "부르지 않아도 된다.",
            input_schema={"type": "object",
                          "properties": {"scopeKey": scope_schema},
                          "required": ["scopeKey"]},
            call=read_wiki_index,
        ),
    ]


# 일정 목록 상한. 백엔드도 자르지만(응답의 `truncated`) 우리 쪽에서도 막는다 — 상한이
# 한 곳에만 있으면 그 한 곳이 바뀔 때 조용히 늘어난다.
MAX_SCHEDULE_ROWS = 50

_SCHEDULE_LIST_KEYS = ("scheduleId", "title", "startAt", "endAt", "targetText",
                       "location")


def build_schedule_tools(client: ChatQueryClient,
                         ledger: ReadLedger) -> list[AgentTool]:
    def list_schedules(**kwargs) -> str:
        # `from` 은 파이썬 예약어라 이름을 그대로 쓸 수 없다. 계약 필드명을 지키기 위해
        # kwargs 로 받는다.
        params = {"questionId": client.question_id,
                  "from": kwargs.get("from"), "to": kwargs.get("to"),
                  "limit": MAX_SCHEDULE_ROWS}
        if kwargs.get("keyword"):
            params["keyword"] = kwargs["keyword"]
        try:
            body = client.get("/internal/v1/schedules", params=params)
        except QueryFailed as failed:
            logger.warning("list_schedules 실패 — %s", failed)
            return f"일정 조회에 실패했습니다: {failed}"
        rows = body.get("items") or []
        text = _rows_text(rows, _SCHEDULE_LIST_KEYS, MAX_SCHEDULE_ROWS)
        if body.get("truncated") and "더 있습니다" not in text:
            text += "\n(기간 안에 일정이 더 있습니다. 기간을 좁혀 다시 부르십시오)"
        return text

    def read_schedule(scheduleId: str) -> str:
        try:
            body = client.get(f"/internal/v1/schedules/{scheduleId}",
                              params={"questionId": client.question_id})
        except QueryFailed as failed:
            logger.warning("read_schedule 실패 — scheduleId=%s: %s", scheduleId, failed)
            return f"일정 조회에 실패했습니다: {failed}"
        title = str(body.get("title") or scheduleId)
        ledger.note_schedule(scheduleId, title)
        return json.dumps({k: body.get(k) for k in (*_SCHEDULE_LIST_KEYS, "content")},
                          ensure_ascii=False)

    return [
        AgentTool(
            name="list_schedules",
            description="기간 안의 일정 목록을 본다. 제목·시각·대상만 오고 내용은 오지 "
                        "않는다. 질문에 맞는 기간을 직접 정해서 부른다 — 「다음」은 오늘 "
                        "이후, 「지난」은 오늘 이전이다. keyword 를 주면 제목으로 좁힌다.",
            input_schema={"type": "object",
                          "properties": {
                              "from": {"type": "string",
                                       "description": "시작일 (YYYY-MM-DD)"},
                              "to": {"type": "string",
                                     "description": "종료일 (YYYY-MM-DD)"},
                              "keyword": {"type": "string",
                                          "description": "제목에 들어갈 말 (선택)"}},
                          "required": ["from", "to"]},
            call=list_schedules,
        ),
        AgentTool(
            name="read_schedule",
            description="일정 하나의 내용을 읽는다. 일정을 근거로 답하려면 이것을 불러야 "
                        "한다.",
            input_schema={"type": "object",
                          "properties": {"scheduleId": {"type": "string"}},
                          "required": ["scheduleId"]},
            call=read_schedule,
        ),
    ]
