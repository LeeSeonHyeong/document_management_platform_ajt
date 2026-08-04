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
#
# **영어 표현이 함께 있어야 한다.** 지시문이 한국어로 쓰여 있어도 모델이 어려운 상황(할 일이
# 없거나 요청을 거절해야 할 때)에서 영어로 빠지는 것을 하네스 실측으로 확인했다
# (2026-08-04, 6회 중 2회). 한국어 키워드만 있으면 "Everything checks out fine" 같은
# 문단이 판별을 그대로 빠져나간다 — 지시문이 금지한 「점검이 끝났다는 말로 시작」 그 자체인데도.
_JARGON_KEYWORDS = (
    # 도구·내부 어휘 (언어 무관)
    "error", "warn", "lint",
    # 한국어 점검 표현
    "오류", "에러", "검증", "통과", "확인 완료",
    # 영어 점검 표현. 관리자에게 보내는 답변이 영어인 것 자체가 이미 잘못이지만, 그때도
    # 최소한 점검 서술은 걷어낸다.
    "checks out", "no changes", "no net changes", "nothing more needed",
    "no issues", "all good", "verified", "validation", "successfully completed",
)
_JARGON_RE = re.compile("|".join(re.escape(k) for k in _JARGON_KEYWORDS), re.IGNORECASE)

# 첫 문단과 나머지를 가르는 경계: 빈 줄(문단 구분) 또는 `---` 구분선.
_PARAGRAPH_BREAK_RE = re.compile(r"\n\s*(?:---\s*)?\n")

# 내부 파일 주소. 백틱·괄호로 감싸는 경우가 많아 그것도 함께 지운다.
_INTERNAL_ADDRESS_RE = re.compile(
    r"[`(]?\b(?:pages|sources)/[\w\-./]+\.md\b[`)]?"
    r"|[`(]?\bdocument-\d+\b[`)]?",
)

# 목차 파일만은 지우지 않고 바꿔치기한다. 지우면 문장의 주어가 사라진다 — 실측에서
# `document-32` 를 지웠더니 "기존 각주 전체가 (존재하지 않는 ID)를 참조하고 있어" 처럼
# 무엇이 참조하는지가 사라졌다. 목차는 관리자가 화면에서 실제로 보는 것이라 부를 이름이 있다.
_INDEX_FILE_RE = re.compile(r"[`(]?\bindex\.md\b[`)]?")
_INDEX_REPLACEMENT = "목차"

# 내부 문서만 가리키는 괄호 묶음. `(document-36 반영)` 처럼 괄호 안이 사실상 내부 식별자
# 하나뿐이면 괄호째 지운다 — 토큰만 지우면 `( 반영)` 이 남는다. 실측: 위키 상세 화면의
# 제목 아래 요약에 "… 사전 승인 절차 (document-32 반영)" 이 그대로 떠 있었다.
_INTERNAL_ONLY_PARENTHETICAL_RE = re.compile(
    r"\s*[(（][^()（）]*\bdocument-\d+\b[^()（）]*[)）]")

# 점검 서술이 든 괄호 묶음. 문단 중간에 이런 괄호로 내부 사정을 덧붙이는 사례가 실측으로
# 확인됐다 — "(이는 이번 편집 전부터 있던 오류였으나, lint가 … error로 잡혔기 때문에 함께
# 수정함)". 괄호 안만 지우고 문장 본체는 살린다: 무엇을 고쳤는지는 관리자가 알아야 한다.
_JARGON_PARENTHETICAL_RE = re.compile(r"\s*[(（][^()（）]*(?:%s)[^()（）]*[)）]"
                                      % "|".join(re.escape(k) for k in _JARGON_KEYWORDS),
                                      re.IGNORECASE)

# 문장 경계. 한국어는 `다.`·`요.`·`습니다.` 로 끝나는 일이 많아 마침표 뒤 공백을 쓴다.
# 완벽한 분해가 목표가 아니다 — 점검 서술 한 문장을 떼어내는 데 필요한 만큼만 자른다.
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+")

# 마크다운 표의 행. 작업 요약이 표 형식이므로(S15P11B106-251) 이 줄들은 문장 단위 제거에서
# 뺀다 — 칸 안의 낱말은 위키 주제이지 점검 서술이 아니다.
_TABLE_ROW_RE = re.compile(r"^\s*\|")

# 작업 요약의 보충 항목. 라벨이 지시문에 고정돼 있어(`ingest_instruction`) 점검 서술 불릿과
# 구별된다. 특히 「반영하지 않은 내용」은 관리자가 누락을 판단하는 근거라, 위키 주제로서
# 「검증」이 들어갔다고 줄째 지우면 정직하게 밝힌 항목이 조용히 사라진다 — 안전망이 오히려
# 숨기는 쪽으로 작동한다. 라벨 없는 점검 불릿(`- lint 검사 통과`)은 계속 지운다.
_SUPPLEMENT_LINE_RE = re.compile(
    r"^\s*[-*]\s*(?:태그|시각 자료|상호 링크|반영하지 않은 내용)\s*:")


def sanitize_admin_reply(text: str) -> str:
    """관리자 채팅에 실어 보내기 직전의 에이전트 답변을 정리한다.

    1. 첫 문단이 잡소리 키워드를 포함하면 그 문단째 버린다(나머지는 그대로 둔다).
    2. `index.md` 는 「목차」로 바꾼다 — 지우면 문장이 깨지고, 관리자는 목차를 화면에서 본다.
    3. 남은 텍스트에서 내부 파일 주소(`pages/*.md`·`document-N` 등) 토큰을 지운다.

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

    stripped = _drop_check_result_prose(stripped)
    cleaned = scrub_internal_tokens(stripped)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned).strip()
    return cleaned or text


def scrub_internal_tokens(text: str) -> str:
    """내부 파일 이름·식별자만 걷어낸다. 점검 서술은 건드리지 않는다.

    목차 요약(`IndexEntry.summary` → `wiki.summary`)에 쓰려고 뺀 것이다. 그 값은 에이전트
    답변이 아니라 목차 마크다운에서 파싱해 온 것이라(`api/changes.py::parse_index_entries`)
    `sanitize_admin_reply` 경로를 지나지 않았고, 그래서 `document-32` 같은 토큰이 위키 상세
    화면의 제목 아래까지 그대로 갔다.

    **요약에는 문장 단위 제거를 걸지 않는다.** 요약은 위키 내용이라 「검증 절차 정리」 같은
    정당한 요약이 점검 서술로 오인될 수 있다 — 그 판단은 답변에만 적용한다.
    """
    if not text:
        return text
    cleaned = _INTERNAL_ONLY_PARENTHETICAL_RE.sub("", text)
    cleaned = _INDEX_FILE_RE.sub(_INDEX_REPLACEMENT, cleaned)
    cleaned = _INTERNAL_ADDRESS_RE.sub("", cleaned)
    cleaned = re.sub(r"[ \t]{2,}", " ", cleaned)
    return cleaned.strip()


def _drop_check_result_prose(text: str) -> str:
    """점검 결과를 말하는 괄호와 문장을 위치와 무관하게 걷어낸다.

    첫 문단만 보던 제약을 푼 것이다. 「오류가 없다」는 정보는 관리자에게 가치가 0이므로
    지워도 잃는 것이 없다 — 과잉 삭제 우려는 실제 정보가 담긴 문장에만 해당한다.

    전부 지워질 상황이면 손대지 않는다. 빈 답변보다는 잡소리라도 남는 편이 낫다는 기존
    판단을 그대로 지킨다.

    **문장 단위라 한계가 있다.** 점검 서술과 실제 정보가 한 문장에 섞이면 그 문장째 사라진다.
    지시문이 점검 이야기를 아예 금지하므로 그런 문장은 드물고, 그때도 남은 문장들이 무엇을
    고쳤는지 전한다.
    """
    without_parentheticals = _JARGON_PARENTHETICAL_RE.sub("", text)

    kept_lines: list[str] = []
    for line in without_parentheticals.splitlines():
        if not line.strip():
            kept_lines.append(line)
            continue
        if _TABLE_ROW_RE.match(line) or _SUPPLEMENT_LINE_RE.match(line):
            # 표 행과 보충 항목은 사실 내용이다. 작업 요약이 표 형식이라 「주요 내용」 칸이나
            # 「태그」에 위키 주제로서 「검증」·「통과」가 들어올 수 있고, 그것을 점검 서술로
            # 오인해 줄을 지우면 관리자가 무슨 페이지가 바뀌었는지·무엇이 빠졌는지 못 본다.
            # 내부 경로는 뒤에서 따로 걷어낸다.
            kept_lines.append(line)
            continue
        sentences = _SENTENCE_SPLIT_RE.split(line)
        survivors = [s for s in sentences if not _JARGON_RE.search(s)]
        if survivors:
            kept_lines.append(" ".join(survivors))
        # 한 줄이 전부 점검 서술이면 그 줄을 버린다 (아래에서 전체가 비면 되돌린다).

    result = "\n".join(kept_lines).strip()
    return result if result else text
