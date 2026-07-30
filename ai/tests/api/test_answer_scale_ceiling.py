"""위키가 커지면 1단계가 언제 멈추는가.

**이 파일은 설계 결함을 고정한다.** `MAX_INPUT_BYTES` 는 "폭주 차단"으로 정한 값인데,
목차가 위키 크기에 비례해 자라므로 **정상 성장도 차단한다.** 어느 규모에서 멈추는지를
숫자로 못박아 두면, 상한을 조정할 때 무엇을 잃고 얻는지 보인다.

실측 기준(2026-07-29): 위키 20페이지 목차 = 6,869바이트 = 343바이트/페이지.
문서 12건이 20페이지를 만들었으므로 **문서 1건 ≈ 1.7페이지 ≈ 580바이트/목차**다.

크레딧을 쓰지 않는다 — 상한 초과는 모델을 부르기 전에 판정된다. 그것 자체가 테스트 대상이다.

**⚠️ 이 천장은 영어 코퍼스 기준이다.** 343바이트/페이지가 `experiments/corpus/` 12건(전부
영어, PostHog 핸드북)에서 나온 위키의 목차 밀도다. 실서비스는 한국어 원본이고 제목·요약이
한국어면 페이지당 바이트가 커져 **천장이 더 낮다.** 한국어 코퍼스 측정 전까지 이 숫자는
상계가 아니라 낙관값이다 (`experiments/corpus/README.md`).
"""

import pytest
from fastapi.testclient import TestClient

from agent_runtime.base import CompletionResult
from wiki_api.answer_selection import MAX_INPUT_BYTES
from wiki_api.app import create_app

API_KEY = "secret-key"
PATH = "/internal/v1/answer-context-selections"

# 실측: 20페이지 목차 6,869바이트. 한 줄이 이 정도다.
BYTES_PER_PAGE = 343


def _index(pages: int) -> str:
    """N페이지 목차. 실측 바이트/페이지에 맞춘 한 줄을 반복한다."""
    lines = ["# 목차\n"]
    for i in range(pages):
        wiki_id = f"{i:012x}"
        pad = "가" * ((BYTES_PER_PAGE - 60) // 3)  # 한글 1자 = 3바이트
        lines.append(f"- [문서 {i}](pages/{wiki_id}.md) — {pad}\n")
    return "".join(lines)


def _request(pages: int) -> dict:
    return {
        "questionId": "500",
        "conversationId": "chat-1",
        "question": "연차 며칠까지 쓸 수 있어?",
        "conversationMessages": [],
        "wikiIndexes": [{"scopeKey": "ALL", "indexMarkdown": _index(pages)}],
        "scheduleSummaries": [],
    }


class CountingRuntime:
    """모델 호출 횟수를 센다. 상한 초과에서 0 이어야 한다 — 크레딧이 나가면 안 된다."""

    name = "counting"

    def __init__(self):
        self.calls = 0

    def complete(self, messages, *, tier="quality", timeout=None):
        self.calls += 1
        return CompletionResult(
            text='{"questionType": "wiki", "wikiIds": [], "scheduleIds": []}')


def _post(pages: int):
    app = create_app(api_key=API_KEY)
    runtime = CountingRuntime()
    app.state.runtime = runtime
    client = TestClient(app, raise_server_exceptions=False)
    response = client.post(PATH, json=_request(pages),
                           headers={"X-Internal-API-Key": API_KEY})
    return response, runtime


@pytest.mark.parametrize("pages", [20, 50, 100])
def test_100페이지까지는_통과한다(pages):
    response, runtime = _post(pages)
    assert response.status_code == 200, response.text
    assert runtime.calls == 1


@pytest.mark.parametrize("pages", [150, 200, 400])
def test_150페이지부터_400_으로_거절한다(pages):
    """**설계 천장이다.** 위키가 이 규모로 자라면 챗봇 1단계가 동작을 멈춘다.

    문서 12건이 20페이지를 만들었으므로 150페이지는 문서 약 90건이다. 사내 규정 문서가
    그 정도는 쉽게 넘는다.
    """
    response, runtime = _post(pages)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ANSWER_CONTEXT_REQUEST"
    # 상한 초과는 모델을 부르기 전에 판정한다 — 크레딧이 나가면 안 된다.
    assert runtime.calls == 0
    # 어느 필드가 얼마나 넘쳤는지 알려줘야 Spring 이 사람에게 설명할 수 있다.
    assert response.json()["fieldErrors"][0]["field"] == "wikiIndexes"
    assert str(MAX_INPUT_BYTES) in response.json()["fieldErrors"][0]["reason"]


def test_천장이_몇_페이지인지_기록한다():
    """정확한 경계를 이진 탐색으로 찾아 상수와 함께 남긴다.

    상한을 조정하면 이 값이 바뀐다. 바뀌는 것이 정상이고, **모르는 채로 바뀌는 것이 문제다.**
    """
    low, high = 1, 1000
    while low < high:
        mid = (low + high + 1) // 2
        response, _ = _post(mid)
        if response.status_code == 200:
            low = mid
        else:
            high = mid - 1
    # 2026-07-29 · MAX_INPUT_BYTES=40,000 기준 약 110페이지.
    # 문서 12건 = 위키 20페이지이므로 문서 약 66건이다.
    assert 90 <= low <= 130, f"천장이 {low}페이지로 바뀌었다 (상한 {MAX_INPUT_BYTES:,}바이트)"
    print(f"\n천장: {low}페이지 (상한 {MAX_INPUT_BYTES:,}바이트) ≈ 문서 {low * 12 // 20}건")
