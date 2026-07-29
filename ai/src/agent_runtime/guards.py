"""런타임이 MCP 서버를 우회했는지 사후에 확인한다.

런타임은 저장소에 오직 서버를 통해서만 닿아야 한다. 권한·참조 그래프·청크 색인이
전부 거기 있기 때문이다. DeepAgents 는 자체 `write_file` 을 들고 오는데, 하네스
프로파일이 그것을 배제하지 못하면 에이전트는 그 셋을 전부 건너뛴 위키를 조용히
만들어낸다. 두 런타임 모두 자기 쪽에서는 그 부재를 증명할 수 없지만, 결과를 보면
알 수 있다 — 변경은 생겼는데 쓰기 툴 호출이 하나도 없으면 파일이 다른 경로로 생긴
것이다.
"""

from __future__ import annotations

WRITE_TOOLS = ("create", "edit", "append", "merge", "delete")


def bypassed_server(changes: list[dict], tool_calls: dict[str, int]) -> bool:
    """무언가가 MCP 툴을 거치지 않고 썼는가.

    툴 호출을 세고 있을 때만 의미가 있다. 빈 로그는 통과가 아니라 판단 불가다.
    """
    if not changes or not tool_calls:
        return False
    return not any(tool_calls.get(name) for name in WRITE_TOOLS)
