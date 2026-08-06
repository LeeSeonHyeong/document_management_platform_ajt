"""압축이 버린 대화 이력을 되읽는 도구 하나.

## 왜 있는가 (2026-08-06 실기동 job 40)

압축 미들웨어는 트리거를 넘으면 옛 대화를 `/conversation_history/{thread_id}.md` 로 버리고
그 자리에 이런 안내를 넣는다 (`deepagents/middleware/summarization.py`):

> The full conversation history has been saved to {file_path} should you need to
> refer back to it for details.

그런데 `EXCLUDED_BUILTIN_TOOLS` 가 `read_file` 을 막고 있다. 그래서 에이전트는 **시킨 대로
읽으려다 실패**하고, 문맥도 복구 수단도 없이 `guide` 부터 다시 시작했다 — 그 실행은 위키를
한 줄도 못 고치고 끝났다.

실측: 모델 호출 11회차 프롬프트 35,771 토큰 → 압축 → 12회차 14,120 토큰. 12번째 도구
호출이 `read /conversation_history/session_….md` 였고 우리 위키 `read` 로 흘러가
`범위 '<br />'를 찾을 수 없다` 로 실패했다.

## 경계 — 왜 `read_file` 을 그냥 풀지 않는가

`EXCLUDED_BUILTIN_TOOLS` 는 **MCP 우회를 막으려고** 있다. 파일 도구가 살아 있으면 에이전트가
`write_file` 로 위키를 직접 써서 권한 경계·참조 그래프·청크 색인을 전부 건너뛴다. 그래서
이 도구는 **압축이 쓴 그 디렉터리 하나만** 읽는다:

* 실제 경로를 만들어 `resolve()` 한 뒤 이력 디렉터리 안인지 확인한다. 문자열 접두어 비교만
  하면 `..` 로 빠져나가고, `/conversation_history_evil/` 같은 이름에도 걸린다
* 쓰기·목록·검색을 제공하지 않는다. 읽기 하나뿐이다

`tests/runtime/test_history_read_tool.py` 가 탈출 경로를 하나씩 고정한다.
"""

from __future__ import annotations

from pathlib import Path

from .tools import AgentTool

# 압축 미들웨어가 쓰는 경로. `summarization.py` 의 `_history_path_prefix` 와 같아야 한다 —
# 그쪽이 바뀌면 에이전트가 받는 안내의 경로도 바뀌므로 여기도 따라가야 한다.
HISTORY_PREFIX = "/conversation_history"

_MAX_CHARS = 40_000


def _history_root(backend) -> Path | None:
    """이력 디렉터리의 실제 경로. backend 가 루트를 안 알려주면 `None`.

    `FilesystemBackend` 는 생성자 인자가 `root_dir` 인데 필드로는 **`cwd`** 로 들고 있다
    (2026-08-06 실측). 둘 다 본다 — 라이브러리가 이름을 되돌려도 깨지지 않게.
    """
    root = getattr(backend, "cwd", None) or getattr(backend, "root_dir", None)
    if root is None:
        return None
    return (Path(root) / HISTORY_PREFIX.lstrip("/")).resolve()


def _resolved_inside(root: Path, file_path: str) -> Path | None:
    """`file_path` 를 실제 경로로 풀고 이력 디렉터리 **안**일 때만 돌려준다.

    `resolve()` 를 거치는 것이 핵심이다 — 문자열 비교만 하면 `..` 가 통과한다.
    """
    relative = file_path.strip()
    # **접두어 뒤에 경계(`/`)를 요구한다.** `startswith` 만 보면 `/conversation_history_evil`
    # 같은 다른 디렉터리가 통과한다 — 탈출은 아니지만 이 도구가 볼 곳이 아니다.
    for prefix in (HISTORY_PREFIX, HISTORY_PREFIX.lstrip("/")):
        if relative == prefix:
            return None                      # 디렉터리 자체는 읽을 것이 없다
        if relative.startswith(prefix + "/"):
            relative = relative[len(prefix):]
            break
    else:
        return None
    candidate = (root / relative.lstrip("/")).resolve()
    if candidate == root or root not in candidate.parents:
        return None
    return candidate


def history_read_tool(backend) -> AgentTool:
    """압축이 버린 이력을 읽는 도구. `backend` 는 압축이 쓰는 그 backend 다."""
    root = _history_root(backend)

    async def call(file_path: str = "") -> str:
        if root is None:
            return "대화 이력을 읽을 수 없다 — 저장 위치를 알 수 없다."
        target = _resolved_inside(root, file_path)
        if target is None:
            return (f"`{file_path}` 는 읽을 수 없다. 이 도구는 `{HISTORY_PREFIX}/` 아래의 "
                    f"대화 이력만 읽는다 — 위키는 `read` 도구로 읽는다.")
        if not target.is_file():
            return f"`{file_path}` 에 해당하는 대화 이력이 없다."
        text = target.read_text(encoding="utf-8", errors="replace")
        if len(text) > _MAX_CHARS:
            # 통째로 넣으면 방금 압축한 것을 도로 되돌려 넣는 꼴이 된다. 뒤쪽(최근)을 남긴다.
            text = "…(앞부분 생략)…\n" + text[-_MAX_CHARS:]
        return text

    return AgentTool(
        name="read_conversation_history",
        description=(
            f"압축으로 잘려나간 이 작업의 옛 대화를 읽는다. 요약 메시지가 "
            f"`{HISTORY_PREFIX}/…md` 경로를 알려주면 그 경로를 그대로 넘긴다. "
            f"**위키 페이지·원본문서는 이 도구로 읽지 않는다** — 그것은 `read` 다."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": f"요약 메시지가 알려준 `{HISTORY_PREFIX}/…md` 경로.",
                },
            },
            "required": ["file_path"],
        },
        call=call,
    )
