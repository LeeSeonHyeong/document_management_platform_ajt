"""스캔 PDF OCR을 GMS 비전 모델(Anthropic Messages API)로 처리하는 어댑터.

로컬 Tesseract 언어팩(kor) 의존을 없애려고, 페이지 이미지를 GMS 게이트웨이의 비전
모델에 보내 글자를 받는다 (S15P11B106-180). 기존 `AnthropicProvider` 와 같은 이유로
SDK 를 새로 넣지 않고 `httpx` 로 직접 부른다 — `transport` 로 테스트가 응답을 흉내낸다.
"""

from __future__ import annotations

import base64
import json

import httpx
import pytest

from document_parser.errors import DocumentParseFailure
from document_parser.vision_ocr import AnthropicVisionOcr

PNG = b"\x89PNG\r\n\x1a\n fake image bytes"


def _engine(handler):
    return AnthropicVisionOcr(
        model="claude-haiku-4-5-20251001", api_key="k",
        base_url="https://gms.example/api.anthropic.com", timeout_seconds=30.0,
        transport=httpx.MockTransport(handler))


def test_sends_the_page_image_as_base64_and_returns_the_text():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = json.loads(request.content)
        seen["key"] = request.headers.get("x-api-key")
        return httpx.Response(200, json={
            "stop_reason": "end_turn",
            "content": [{"type": "text", "text": "회의 운영\n주간 회의는 30분."}]})

    text = _engine(handler).ocr_image(PNG)

    assert text == "회의 운영\n주간 회의는 30분."
    assert seen["url"].endswith("/v1/messages")
    assert seen["key"] == "k"
    # 이미지가 base64 image 블록으로 실린다.
    block = seen["body"]["messages"][0]["content"][0]
    assert block["type"] == "image"
    assert block["source"]["media_type"] == "image/png"
    assert base64.b64decode(block["source"]["data"]) == PNG


def test_http_error_becomes_a_structured_ocr_failure():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    with pytest.raises(DocumentParseFailure) as caught:
        _engine(handler).ocr_image(PNG)
    assert caught.value.code == "ocr_failed"


def test_a_response_without_text_is_empty_not_an_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"stop_reason": "end_turn", "content": []})

    assert _engine(handler).ocr_image(PNG) == ""
