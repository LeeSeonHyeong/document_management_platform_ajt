"""1단계 — 문맥 선택 (`POST /internal/v1/wiki-context-selections`, 설계 §5).

**에이전트가 아니다.** 입력이 목차와 문서 본문뿐이라 툴이 필요 없고, 출력은 wikiId 목록
하나다. 그래서 MCP 서버를 띄우지 않고 런타임에 한 번만 묻는다 (`runtime/base.py` 의
`Runtime.complete` 주석 참고 — 두 프로덕션 런타임은 아직 그 메서드가 없어서 `run`/`arun`
으로 물러선다).

**모델이 낸 ID 를 믿지 않는다.** 목차 링크(`pages/{wikiId}.md`)에 실재하는 ID 만 남기고,
중복을 지우고, 5개에서 자른다. 이 세 가지가 여기 있는 이유는 다음 단계 때문이다 — Spring
은 이 응답의 ID 로 본문을 읽어 2단계 변환의 하이드레이션에 싣는다. 지어낸 ID 가 새면
Spring 이 없는 위키를 조회하거나(다행) 다른 범위의 위키를 읽는다(사고). Spring 도 재검증
하지만(계약 정책), 우리가 먼저 거른다.

빈 결과는 정상이다 — 관련된 위키가 없으면 2단계는 라이브 층 없이 새 페이지를 만든다.
"""

from __future__ import annotations

import json
import re

from agent_runtime.base import FAST, selection_instruction

from .completion import complete
from .schemas import SelectionRequest, SelectionResponse

# 계약의 상한 (`docs/FastAPI명세서.json` — "중복 없이 관련도 순서로 최대 5개").
MAX_WIKIS = 5

# 단발 호출이다. 에이전트 루프(최대 30분)와 같은 예산을 줄 이유가 없다 — 여기서 오래
# 걸리는 것은 진행이 아니라 고장이고, 2단계가 시작도 못 한 채 Spring 의 읽기 타임아웃을
# 먹는다.
SELECTION_TIMEOUT_SECONDS = 300

ERROR_CODE = "WIKI_CONTEXT_SELECTION_FAILED"
PATH = "/internal/v1/wiki-context-selections"

# 목차의 페이지 링크. v1.1.0 예시가 `pages/{wikiId}.md` 다 (설계 §3).
_INDEX_LINK_RE = re.compile(r"pages/([A-Za-z0-9_-]+)\.md")

# 자유 텍스트 응답에서 ID 후보를 뽑는다. 목차에 실재하는 것만 남기므로 넉넉해도 된다.
_TOKEN_RE = re.compile(r"[A-Za-z0-9_-]+")


def index_wiki_ids(current_index: str) -> list[str]:
    """목차 링크에 실재하는 wikiId — 등장 순서, 중복 제거."""
    found: list[str] = []
    for wiki_id in _INDEX_LINK_RE.findall(current_index or ""):
        if wiki_id not in found:
            found.append(wiki_id)
    return found


def parse_selection(text: str, current_index: str) -> tuple[list[str], str]:
    """모델 응답 → (wikiIds, reason).

    JSON 이 정본이지만 그것에 의존하지 않는다. 모델이 설명을 앞뒤로 붙이거나 코드펜스를
    두르는 일이 흔하고, 그때 파싱 실패로 선택을 통째로 버리면 2단계가 문맥 없이 돈다 —
    자유 텍스트에서도 ID 를 줍는다. 어느 경로든 마지막 관문은 같다: 목차에 있는 ID 만.
    """
    allowed = index_wiki_ids(current_index)
    payload = _json_object(text or "")

    reason = ""
    candidates: list[str] | None = None
    if payload is not None:
        raw_reason = payload.get("reason")
        reason = str(raw_reason).strip() if isinstance(raw_reason, (str, int, float)) else ""
        raw_ids = payload.get("wikiIds")
        if isinstance(raw_ids, list):
            candidates = [str(item).strip() for item in raw_ids]

    if candidates is None:
        # JSON 이 아니거나 `wikiIds` 가 없다 — 본문에서 줍는다.
        candidates = _TOKEN_RE.findall(text or "")
    if not reason:
        reason = (text or "").strip()

    picked: list[str] = []
    for candidate in candidates:
        if candidate in allowed and candidate not in picked:
            picked.append(candidate)
            if len(picked) == MAX_WIKIS:
                break
    return picked, reason


def _json_object(text: str) -> dict | None:
    """텍스트 안의 첫 JSON 객체. 코드펜스·앞뒤 설명을 견딘다."""
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


async def select_wikis(runtime, payload: SelectionRequest, *,
                       request_id: str = "") -> SelectionResponse:
    """목차만 보고 이번 변환에 필요한 위키를 고른다.

    호출은 `wiki_api/completion.py` 를 지난다. 런타임의 `complete` 를 직접 부르면 sync
    반환이 이벤트 루프를 최대 `SELECTION_TIMEOUT_SECONDS` 동안 막는다 — 그 사이 서버의
    모든 요청이 대기한다.

    tier 는 `fast` 다. 목차에서 ID 를 고르는 일이고, 모델이 지어낸 ID 는 `parse_selection`
    이 화이트리스트로 거르고 Spring 이 다시 재검증한다.
    """
    prompt = selection_instruction(payload.parsedMarkdown, payload.currentIndex,
                                   payload.changeType, payload.removedParsedMarkdown)
    result = await complete(
        runtime, [{"role": "user", "content": prompt}], tier=FAST,
        timeout=SELECTION_TIMEOUT_SECONDS, error_code=ERROR_CODE, path=PATH,
        request_id=request_id,
        # `complete` 가 없는 런타임은 빈 임시 루트로 띄운다 (`completion._fallback`).
        fallback_kwargs={"scope_key": payload.scopeKey, "job_id": payload.jobId},
    )
    wiki_ids, reason = parse_selection(result.text, payload.currentIndex)
    if not reason:
        reason = "관련된 위키를 찾지 못했습니다." if not wiki_ids else "선택 근거가 없습니다."
    return SelectionResponse(wikiIds=wiki_ids, reason=reason)
