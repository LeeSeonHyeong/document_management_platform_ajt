"""각주 정의가 **이번 작업 전부터 있었나**를 판정한다.

이 판정이 필요한 곳은 둘이다 — 에이전트가 읽는 `lint` 보고서(`tools/lint.py`)와 반영
게이트(`wiki_api/session.py::assert_lint_clean`). **한쪽에만 있으면 에이전트가 게이트가
용서할 오류를 고치려고 턴을 다 쓴다.** 실측(2026-08-05, job 32/document 43): 같은 4건이
6번의 `lint` 와 4번의 `edit` 을 거쳐 하나도 줄지 않고 턴 상한에서 죽었다 — 도구 46라운드,
137만 토큰, $0.72, 산출물 0.
(spec: docs/superpowers/specs/2026-08-05-lint-gate-parity-design.md)

`wiki_api` 가 아니라 여기 있는 이유는 의존 방향이다. `wiki_mcp` 는 위쪽을 임포트할 수 없으므로
게이트가 이 판정을 쓰는 방향만 성립하고, 판정에 필요한 것이 VaultFS 원시 연산뿐이라 그대로
내려온다.

**이름이 아니라 나이로 판정한다.** 파일명으로 가려내려던 앞 판본은 죽은 분기였다 —
`find_source` 가 파일명·확장자 없는 이름·`source_id`·주소를 모두 매칭하므로 이번 요청이 올린
문서를 가리키는 각주는 애초에 `unresolved-citation` 까지 오지 않는다. 그 규칙은 결과적으로
**모든** 미해결 인용을 버려 지어낸 인용이 게이트를 통과했다 (NFR-AI-002 상실).
"""

from __future__ import annotations

import re

# `tools/lint.py`·`wiki_api/session.py` 가 각자 갖고 있던 것과 같은 정규식이다. 판정이 이
# 모듈로 모였으므로 정본도 여기다.
_FOOTNOTE_DEF_RE = re.compile(r"^\[\^([^\]]+)\]:\s*(.+)$", re.MULTILINE)


def definition_lines(content: str) -> dict[str, str]:
    """각주 라벨 → 정규화한 정의 줄."""
    return {label: f"[^{label}]: {raw.strip()}"
            for label, raw in _FOOTNOTE_DEF_RE.findall(content)}


async def legacy_footnote_labels(fs, scope_id: str, address: str,
                                 content: str) -> set[str]:
    """`content` 의 각주 중 **정의 줄이 라이브 층에 그대로 있는** 것들의 라벨.

    `content` 를 인자로 받는다 — 호출자가 이미 본문을 들고 있고, 여기서 `get` 을 다시 부르면
    조회 API 경로에서 본문을 한 번 더 당긴다.

    **라벨이 아니라 정의 줄 전체로 비교한다.** 라벨만 보면 같은 번호로 내용을 바꿔 쓴 각주가
    「원래 있던 것」으로 통과한다 — 지어낸 인용이 새는 바로 그 구멍이다.

    라이브 행이 없으면(= 이번 작업이 새로 만든 페이지) 빈 집합이다. 그 페이지의 각주는 전부
    이번 작업이 쓴 것이므로 하나도 면제되지 않는다.
    """
    current = definition_lines(content)
    if not current:
        return set()
    live = await fs.live_content(scope_id, address)
    if not live:
        return set()
    live_lines = set(definition_lines(live).values())
    return {label for label, line in current.items() if line in live_lines}
