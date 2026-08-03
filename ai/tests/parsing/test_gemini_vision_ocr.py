"""스캔 PDF OCR을 Google Gemini `generateContent` API로 처리하는 어댑터.

GMS 게이트웨이는 Google을 프록시하지 않으므로 이 어댑터는 Google API에 직접 붙는다
(`gemini_vision_ocr.py` 참고). 응답은 `tool_config.function_calling_config.mode`
로 강제한 `emit_ocr_text` 함수 호출의 `args` 로 온다 — 자유 텍스트가 아니다.
"""

from __future__ import annotations

import base64
import json

import httpx
import pytest

from document_parser.errors import DocumentParseFailure
from document_parser.gemini_vision_ocr import GeminiVisionOcr

PNG = b"\x89PNG\r\n\x1a\n fake image bytes"


def _engine(handler, sleeps: list | None = None):
    return GeminiVisionOcr(
        api_key="k", model="gemini-2.0-flash",
        base_url="https://generativelanguage.example",
        timeout_seconds=30.0,
        transport=httpx.MockTransport(handler),
        sleep=(sleeps.append if sleeps is not None else lambda _seconds: None))


def _function_call_response(*, has_readable_text: bool, text: str,
                            finish_reason: str = "STOP") -> dict:
    return {
        "candidates": [{
            "finishReason": finish_reason,
            "content": {"parts": [{
                "functionCall": {
                    "name": "emit_ocr_text",
                    "args": {"has_readable_text": has_readable_text, "text": text},
                },
            }]},
        }],
    }


def test_sends_the_page_image_as_inline_data_and_forces_the_ocr_function():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = json.loads(request.content)
        seen["key"] = request.headers.get("x-goog-api-key")
        return httpx.Response(200, json=_function_call_response(
            has_readable_text=True, text="회의 운영\n주간 회의는 30분."))

    text = _engine(handler).ocr_image(PNG)

    assert text == "회의 운영\n주간 회의는 30분."
    assert seen["url"].endswith("/v1beta/models/gemini-2.0-flash:generateContent")
    assert seen["key"] == "k"
    part = seen["body"]["contents"][0]["parts"][1]
    assert part["inline_data"]["mime_type"] == "image/png"
    assert base64.b64decode(part["inline_data"]["data"]) == PNG
    # 자유 텍스트가 아니라 함수 호출을 강제한다.
    fcc = seen["body"]["tool_config"]["function_calling_config"]
    assert fcc == {"mode": "ANY", "allowed_function_names": ["emit_ocr_text"]}


def test_http_error_becomes_a_structured_ocr_failure():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    with pytest.raises(DocumentParseFailure) as caught:
        _engine(handler).ocr_image(PNG)
    assert caught.value.code == "ocr_failed"


def test_has_readable_text_false_is_empty_not_an_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_function_call_response(
            has_readable_text=False,
            text="죄송하지만 이미지가 흐릿해서 글자를 읽을 수 없습니다."))

    assert _engine(handler).ocr_image(PNG) == ""


def test_no_candidates_from_a_safety_block_is_a_failure():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "promptFeedback": {"blockReason": "SAFETY"}, "candidates": []})

    with pytest.raises(DocumentParseFailure) as caught:
        _engine(handler).ocr_image(PNG)
    assert caught.value.code == "ocr_failed"


def test_no_candidates_without_a_block_reason_is_empty_not_an_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"candidates": []})

    assert _engine(handler).ocr_image(PNG) == ""


def test_a_response_without_a_function_call_is_empty_not_an_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "candidates": [{"finishReason": "STOP",
                            "content": {"parts": [{"text": "no function call"}]}}]})

    assert _engine(handler).ocr_image(PNG) == ""


def test_truncated_output_at_max_tokens_is_a_failure_not_partial_text():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_function_call_response(
            has_readable_text=True, text="잘린 본문", finish_reason="MAX_TOKENS"))

    with pytest.raises(DocumentParseFailure) as caught:
        _engine(handler).ocr_image(PNG)
    assert caught.value.code == "ocr_failed"


def test_retries_on_a_5xx_then_succeeds():
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] < 3:
            return httpx.Response(503, text="overloaded")
        return httpx.Response(200, json=_function_call_response(
            has_readable_text=True, text="됨"))

    sleeps: list[float] = []
    assert _engine(handler, sleeps).ocr_image(PNG) == "됨"
    assert calls["n"] == 3
    assert sleeps == [1.0, 2.0]


def test_retries_on_429_then_succeeds():
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] < 2:
            return httpx.Response(429, text="rate limited")
        return httpx.Response(200, json=_function_call_response(
            has_readable_text=True, text="됨"))

    assert _engine(handler, []).ocr_image(PNG) == "됨"
    assert calls["n"] == 2


def test_retries_on_a_network_error_then_succeeds():
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] < 2:
            raise httpx.ConnectError("connection refused", request=request)
        return httpx.Response(200, json=_function_call_response(
            has_readable_text=True, text="됨"))

    assert _engine(handler, []).ocr_image(PNG) == "됨"
    assert calls["n"] == 2


def test_gives_up_after_max_attempts_on_persistent_5xx():
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(503, text="overloaded")

    with pytest.raises(DocumentParseFailure) as caught:
        _engine(handler, []).ocr_image(PNG)
    assert caught.value.code == "ocr_failed"
    assert calls["n"] == 3  # 최초 1회 + 재시도 2회, 그 이상은 안 부른다


def test_does_not_retry_a_non_retryable_4xx():
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(401, text="unauthorized")

    with pytest.raises(DocumentParseFailure) as caught:
        _engine(handler, []).ocr_image(PNG)
    assert caught.value.code == "ocr_failed"
    assert calls["n"] == 1  # 재시도하지 않는다 — 다시 불러도 똑같이 401


def test_uses_the_default_model_and_base_url_when_not_given():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200, json=_function_call_response(
            has_readable_text=True, text="됨"))

    engine = GeminiVisionOcr(api_key="k", transport=httpx.MockTransport(handler),
                             sleep=lambda _s: None)
    engine.ocr_image(PNG)
    assert seen["url"] == (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        "gemini-flash-lite-latest:generateContent")
