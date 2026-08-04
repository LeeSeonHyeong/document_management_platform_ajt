"""관리자에게 보내는 에이전트 답변에서 내부 용어가 새는 것을 마지막에 한 번 더 막는다.

`edit_instruction`·`ingest_instruction`(`base.py`)이 이미 "비개발자 용어로 쓰라"고 강하게
지시하지만, 모델이 그 지시를 어기고 lint 도구가 돌려주는 어휘(error·warn)나 내부 파일
경로(`pages/xxxx.md`, `document-N`)를 답변에 그대로 옮기는 사례가 실측으로 확인됐다
(2026-08-04, S15P11B106-243 검증 중). 프롬프트 지시는 확률적이라 100%를 보장하지 않으므로,
이 모듈은 그 지시가 새어나간 경우를 잡는 마지막 안전망이다 — 정규식 기반이라 비용이 없고,
애매하면 손대지 않는다(과잉 삭제가 과소 삭제보다 위험하다: 진짜 답변 내용을 지우면 관리자가
무엇이 바뀌었는지 아예 모르게 된다).
"""

from __future__ import annotations

import re

# 잡소리 문단 판별 키워드. 대소문자 구분 없이, 첫 문단에 하나라도 있으면 그 문단을 버린다.
_JARGON_KEYWORDS = (
    "error", "warn", "lint",
    "오류", "에러", "검증", "통과", "확인 완료",
)
_JARGON_RE = re.compile("|".join(re.escape(k) for k in _JARGON_KEYWORDS), re.IGNORECASE)

# 첫 문단과 나머지를 가르는 경계: 빈 줄(문단 구분) 또는 `---` 구분선.
_PARAGRAPH_BREAK_RE = re.compile(r"\n\s*(?:---\s*)?\n")

# 내부 파일 주소. 백틱·괄호로 감싸는 경우가 많아 그것도 함께 지운다.
_INTERNAL_ADDRESS_RE = re.compile(
    r"[`(]?\b(?:pages|sources)/[\w\-./]+\.md\b[`)]?"
    r"|[`(]?\bdocument-\d+\b[`)]?",
)


def sanitize_admin_reply(text: str) -> str:
    """관리자 채팅에 실어 보내기 직전의 에이전트 답변을 정리한다.

    1. 첫 문단이 잡소리 키워드를 포함하면 그 문단째 버린다(나머지는 그대로 둔다).
    2. 남은 텍스트에서 내부 파일 주소(`pages/*.md`·`document-N` 등) 토큰을 지운다.

    빈 텍스트나 잡소리 문단이 전부인 경우는 원문을 그대로 돌려준다 — 지울수록 안전한 게
    아니라, 관리자에게 아무 답도 못 보여주는 게 더 나쁘다.
    """
    if not text:
        return text

    stripped = text
    match = _PARAGRAPH_BREAK_RE.search(text)
    if match:
        first_paragraph = text[:match.start()]
        rest = text[match.end():]
        if _JARGON_RE.search(first_paragraph) and rest.strip():
            stripped = rest

    cleaned = _INTERNAL_ADDRESS_RE.sub("", stripped)
    cleaned = re.sub(r"[ \t]{2,}", " ", cleaned)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned).strip()
    return cleaned or text
