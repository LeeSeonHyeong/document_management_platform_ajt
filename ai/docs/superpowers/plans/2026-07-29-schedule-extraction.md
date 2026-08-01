# 일정 추출 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /internal/v1/schedule-extractions` 를 신설해 일정 문서 업로드 기능을 살린다. 백엔드가 이미 그 경로를 부르고 있어 지금은 404 로 죽어 있다.

**Architecture:** 모델은 판단하고 코드는 계산한다. LLM 은 문서에 적힌 지역 시각을 naive 문자열로 옮기기만 하고, `Asia/Seoul` → UTC 변환·연도 추론·순서 부여·상태 판정·탈락 판정은 순수 함수가 한다. LLM 경계는 함수 하나(`complete_json`)이므로 테스트 대부분이 모델 없이 돈다.

**Tech Stack:** Python 3.12, uv, FastAPI, pydantic v2, pytest, `zoneinfo`, `httpx`, ollama(로컬 측정)

**설계 근거:** `../specs/2026-07-29-schedule-extraction-design.md` — 절 번호로 참조한다.

**작업 위치:** 워크트리 `/home/wolyong/workspace/project/AJT/S15P11B106-79-schedule`, 브랜치 `feature/S15P11B106-79-ai-schedule-extraction` (`origin/develop` 기준)

## Global Constraints

- **`ai/` 밖을 수정하지 않는다.** `backend/`·`frontend/`·`docs/`·루트 파일은 담당자가 따로 있다 (`ai/CLAUDE.md`).
- **stage 는 `ai/` 하위 경로만 명시한다.** `git add -A`·`git add .` 를 쓰지 않는다. 커밋 전 `git status` 로 확인한다.
- **계약이 이 경로에 허용한 상태는 400·401·500 뿐이다.** 그 밖을 내면 Spring 이 `UNEXPECTED_STATUS` 로 뭉갠다.
- **400 코드와 메시지는 계약이 확정했다:** `INVALID_SCHEDULE_EXTRACTION_REQUEST` / `"일정 추출 요청 구조가 올바르지 않습니다."`
- **500 코드:** `SCHEDULE_EXTRACTION_FAILED`
- **ID 는 전부 문자열이다.** `BIGINT UNSIGNED` 가 JSON number 로 나가면 정밀도를 잃는다 (`schemas.py:18`).
- **시각은 RFC 3339 UTC `Z`.** 백엔드가 `Instant.parse` 한다 (`RestClientAiClient:199`).
- **`schedules`·`warnings` 는 `null` 금지.** 빈 배열이어야 한다 (`RestClientAiClient:176`).
- **의존 방향은 `wiki_api → schedule_extractor` 단방향.** `schedule_extractor` 는 `wiki_api`·`wiki_mcp`·`agent_runtime` 을 임포트하지 않는다.
- **새 패키지는 `pyproject.toml` 의 `[tool.hatch.build.targets.wheel] packages` 에 등록한다.** editable 설치가 `src/` 를 `sys.path` 에 올리므로 테스트는 등록 없이도 통과하지만 wheel 빌드에서 빠져 배포에서 `ModuleNotFoundError` 가 난다.
- **커밋 메시지는 한국어 본문, `feat(ai):`·`test(ai):`·`docs(ai):` 접두어** (`docs/conventions/git-convention.md`).
- **Claude 트레일러를 붙이지 않는다.**
- 기본 테스트 실행: `uv run pytest -m "not ocr and not llm"`

---

## File Structure

| 파일 | 책임 |
| --- | --- |
| `src/schedule_extractor/__init__.py` | 공개 함수 `extract_schedules` 와 자료형 `ExtractedSchedule`·`Extraction` |
| `src/schedule_extractor/prompt.py` | 프롬프트 문자열과 모델 출력 JSON Schema |
| `src/schedule_extractor/normalize.py` | 순수 함수. KST→UTC · 연도 추론 · order · 탈락 · 누락 경고 |
| `src/schedule_extractor/date_probe.py` | 문서에 날짜 패턴이 있나 (§3.3 두 번째 줄) |
| `src/schedule_extractor/provider.py` | 포트 `JsonCompletionProvider` 와 `ProviderError` |
| `src/schedule_extractor/providers/ollama.py` | 로컬 어댑터 |
| `src/schedule_extractor/providers/anthropic.py` | 배포 어댑터 |
| `src/schedule_extractor/config.py` | 어댑터 선택·모델·`base_url`·타임아웃 |
| `src/wiki_api/schemas.py` | 계약 요청·응답 모델 (파일 끝에 추가) |
| `src/wiki_api/errors.py` | 경로별 400·500 코드 등록 |
| `src/wiki_api/routers/schedule.py` | HTTP 경계. status 3분기 · 교차 검증 · 예외 변환 |
| `src/wiki_api/app.py` | `include_router` · `app.state.schedule_provider` |
| `tests/schedule/` | `test_normalize.py` · `test_date_probe.py` · `test_prompt.py` · `test_local_model.py` |
| `tests/api/test_api_schedule.py` | 라우터 경계 |
| `experiments/corpus-schedule/` | 합성 일정 문서와 정답 JSON |

`prompt.py` 와 `normalize.py` 를 나누는 이유: 프롬프트는 모델을 바꾸면 흔들리고 정규화는 안 흔들린다. 회귀 테스트의 수명이 다르다.

---

### Task 1: 계약 경계 — 스키마·오류 코드·라우터 골격

**Files:**
- Create: `src/schedule_extractor/__init__.py`
- Create: `src/schedule_extractor/provider.py`
- Create: `src/wiki_api/routers/schedule.py`
- Create: `tests/schedule/__init__.py`
- Create: `tests/api/test_api_schedule.py`
- Modify: `src/wiki_api/schemas.py` (파일 끝)
- Modify: `src/wiki_api/errors.py:69-92`
- Modify: `src/wiki_api/app.py:32-36`

**Interfaces:**
- Produces: `ScheduleExtractionRequest`·`ScheduleExtractionResponse`·`ExtractedScheduleOut` (pydantic), `JsonCompletionProvider` Protocol, `ProviderError`, `Extraction`·`ExtractedSchedule` dataclass, `build_router(app) -> APIRouter`
- Consumes: 없음

이 Task 는 **모델 없이 도는 껍데기**를 만든다. 추출은 다음 Task 들이 채운다. 계약 위반 위험이 가장 큰 곳이므로 먼저 잠근다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/api/test_api_schedule.py`:

```python
"""schedule-extractions — 일정 문서에서 초안을 뽑는다.

상태가 없다. 요청 본문의 parsedMarkdown 하나만 보고 결과를 낸다 — VaultFS·MCP·작업층을
쓰지 않는다. LLM 경계는 app.state.schedule_provider 라서 테스트가 그것을 교체한다
(test_api_edits.py 의 app.state.runtime 과 같은 방식).
"""

from fastapi.testclient import TestClient

from wiki_api.app import create_app

API_KEY = "test-internal-key"

DOC = """# 2026년 8월 사내 일정

## 하계 워크샵
- 일시: 8월 12일 09:00 ~ 8월 13일 18:00
- 장소: 강원도 속초 리조트
- 대상: 개발부 전 직원
"""

REQUEST = {
    "sourceGroupKey": "schedule-source-20260727-01",
    "parsedMarkdown": DOC,
    "visibilityType": "department",
    "departmentIds": ["1", "2"],
}


class FakeProvider:
    """complete_json 하나만 흉내낸다. 포트가 함수 하나라 가짜도 짧다."""

    def __init__(self, payload=None, error=None):
        self.payload = payload if payload is not None else {"schedules": [], "warnings": []}
        self.error = error
        self.calls = 0

    async def complete_json(self, prompt: str, schema: dict) -> dict:
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.payload


def _client(provider):
    app = create_app(api_key=API_KEY)
    app.state.schedule_provider = provider
    return TestClient(app, raise_server_exceptions=False)


def test_no_api_key_is_401():
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions", json=REQUEST)
    assert response.status_code == 401
    assert response.json()["code"] == "INVALID_INTERNAL_API_KEY"


def test_blank_parsed_markdown_is_400():
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "parsedMarkdown": "   "},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_SCHEDULE_EXTRACTION_REQUEST"
    assert response.json()["message"] == "일정 추출 요청 구조가 올바르지 않습니다."
    assert [error["field"] for error in response.json()["fieldErrors"]] == ["parsedMarkdown"]


def test_unknown_visibility_type_is_400():
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "visibilityType": "personal"},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_SCHEDULE_EXTRACTION_REQUEST"


def test_extra_field_is_400_not_422():
    """FastAPI 기본값은 422 다. 팀 규약은 400 + 공통 구조다 (errors.py 도입부)."""
    response = _client(FakeProvider()).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "referenceDate": "2026-08-01"},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 400


def test_empty_document_returns_no_schedule():
    """날짜가 없는 문서에 모델이 0건을 냈다 — 일정이 없는 문서다 (§3.3 첫째 줄)."""
    provider = FakeProvider({"schedules": [], "warnings": []})
    response = _client(provider).post(
        "/internal/v1/schedule-extractions",
        json={**REQUEST, "parsedMarkdown": "# 문의처\n인사팀 내선 1234\n"},
        headers={"X-Internal-Api-Key": API_KEY})
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "no_schedule"
    assert body["schedules"] == []
    assert body["warnings"] == []
    assert provider.calls == 1
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_api_schedule.py -v`
Expected: 전부 FAIL. 404 또는 `ImportError`

- [ ] **Step 3: 포트와 자료형을 만든다**

`src/schedule_extractor/provider.py`:

```python
"""LLM 경계. 포트가 함수 하나라 어댑터도 가짜도 짧다.

여기서 끝나는 것: 프롬프트를 주면 스키마에 맞는 dict 를 돌려준다.
여기서 하지 않는 것: 재시도·프롬프트 조립·결과 해석. 위층이 한다.
"""

from __future__ import annotations

from typing import Protocol


class ProviderError(Exception):
    """어댑터가 결과를 못 낸 모든 경우 — 전송 실패·타임아웃·스키마 위반.

    이 예외의 문자열에는 URL·모델명·응답 조각이 들어갈 수 있다. 라우터가 이것을 잡아
    고정 메시지로 바꾸고 상세는 로그에만 남긴다 (설계 §3.6).
    """


class JsonCompletionProvider(Protocol):
    async def complete_json(self, prompt: str, schema: dict) -> dict: ...
```

`src/schedule_extractor/__init__.py`:

```python
"""일정 문서에서 개별 일정을 뽑는다.

**모델은 판단하고 코드는 계산한다.** 모델에게 맡기는 것은 "이것이 일정인가",
"제목이 무엇인가", "문서가 적은 시각이 무엇인가" 다. 시간대 변환·연도 추론·순서
부여·탈락 판정은 normalize.py 가 한다.

측정 근거 — 모델에게 KST→UTC 변환을 시키면 09:00 KST 를 09:00Z 로 낸다.
코드가 변환하면 맞는다 (설계 §2.1).

이 패키지는 FastAPI 를 모르고 계약 스키마도 모른다. wiki_api 가 계약 모양을 안다.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from .provider import JsonCompletionProvider, ProviderError

__all__ = ["ExtractedSchedule", "Extraction", "JsonCompletionProvider",
           "ProviderError", "extract_schedules"]


@dataclass(frozen=True)
class ExtractedSchedule:
    order: int
    title: str
    content: str | None      # 없으면 warning. FR-SCH-001 이 추출을 요구한다
    target_text: str | None  # 같음
    location: str | None     # 요구 대상 아니다. 없어도 조용히 넘어간다
    start_at: datetime       # tz-aware UTC
    end_at: datetime


@dataclass(frozen=True)
class Extraction:
    schedules: tuple[ExtractedSchedule, ...]
    warnings: tuple[str, ...]
    raw_count: int
    """모델이 낸 건수. 정규화가 살린 건수와 다르면 탈락이 있었다는 뜻이고,
    라우터의 status 3분기가 이 값을 본다 (설계 §3.3)."""


async def extract_schedules(markdown: str, *, now: datetime,
                            provider: JsonCompletionProvider) -> Extraction:
    """일정 추출 1회. 모델 호출 1번 + 정규화.

    now 는 tz-aware UTC 로 받는다. 연도가 없는 날짜의 기준 연도를 정하는 데만 쓴다.
    """
    from .normalize import normalize
    from .prompt import OUTPUT_SCHEMA, build_prompt

    raw = await provider.complete_json(build_prompt(markdown, now=now), OUTPUT_SCHEMA)
    items = raw.get("schedules") or []
    schedules, warnings = normalize(raw, now=now)
    return Extraction(schedules=schedules, warnings=warnings, raw_count=len(items))
```

- [ ] **Step 4: 계약 스키마를 더한다**

`src/wiki_api/schemas.py` 파일 끝에 붙인다:

```python
# ---- 일정 추출 (schedule-extractions) --------------------------------------
#
# 계약 v1.3.1. 응답 필드 이름과 형태를 계약 Saved Example 이 정했다.
#
# `parsedMarkdown` 바이트 상한은 계약에 없다. 업로드 파일이 20MB 이하이고
# (FR-DOC-003) 파싱 결과가 원본보다 커지는 경우는 드물어 그로부터 유도한다.
# 계약상 유효한 요청을 막지 않도록 넉넉히 잡는다 — MR 협의 항목이다.
MAX_SCHEDULE_MARKDOWN_BYTES = 24 * 1024 * 1024

ScheduleVisibility = Literal["all", "department"]


class ScheduleExtractionRequest(Strict):
    sourceGroupKey: str = Field(min_length=1)
    parsedMarkdown: str
    visibilityType: ScheduleVisibility
    departmentIds: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def _the_document_must_have_content(self) -> "ScheduleExtractionRequest":
        """빈 문서로 부르면 모델을 태울 이유가 없다 — 400 으로 되돌린다.

        공백만 있는 경우도 막는다. 파싱이 실패했는데 빈 문자열로 성공한 것처럼
        온 경우가 여기서 걸린다.
        """
        errors: list[InitErrorDetails] = []
        if not self.parsedMarkdown.strip():
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "schedule_markdown_blank", "parsedMarkdown 이 비어 있습니다."),
                loc=("parsedMarkdown",), input=self.parsedMarkdown))
        size = len(self.parsedMarkdown.encode("utf-8"))
        if size > MAX_SCHEDULE_MARKDOWN_BYTES:
            errors.append(InitErrorDetails(
                type=PydanticCustomError(
                    "schedule_markdown_too_large",
                    "parsedMarkdown 이 상한을 넘었습니다: {size} 바이트",
                    {"size": size}),
                loc=("parsedMarkdown",), input=size))
        if errors:
            raise ValidationError.from_exception_data(self.__class__.__name__, errors)
        return self


class ExtractedScheduleOut(Strict):
    """계약 응답의 일정 1건. 시각은 문자열이다 — 백엔드가 Instant.parse 한다."""

    order: int
    title: str
    content: str | None = None
    targetText: str | None = None
    location: str | None = None
    visibilityType: ScheduleVisibility
    departmentIds: list[str] = Field(default_factory=list)
    startAt: str
    endAt: str


class ScheduleExtractionResponse(Strict):
    status: Literal["extracted", "no_schedule"]
    schedules: list[ExtractedScheduleOut] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
```

- [ ] **Step 5: 오류 코드를 등록한다**

`src/wiki_api/errors.py` 의 `_VALIDATION_CODES` 에 한 줄, `_FAILURE_CODES` 에 한 줄 더한다. 문장은 계약 Saved Example 그대로다.

```python
# _VALIDATION_CODES 안
    "/internal/v1/schedule-extractions": (
        "INVALID_SCHEDULE_EXTRACTION_REQUEST", "일정 추출 요청 구조가 올바르지 않습니다."),
```

```python
# _FAILURE_CODES 안
    "/internal/v1/schedule-extractions": "SCHEDULE_EXTRACTION_FAILED",
```

- [ ] **Step 6: 라우터를 만든다**

`src/wiki_api/routers/schedule.py`:

```python
"""일정 추출 — `POST /internal/v1/schedule-extractions` (계약 v1.3.1).

Spring 의 일정 원본문서 업로드가 파싱 직후 동기로 부른다. 상태가 없다 — 요청 본문의
`parsedMarkdown` 하나만 보고 결과를 낸다.

이 모듈은 **경계만** 맡는다: 계약 요청을 추출기 입력으로, 추출기 결과를 계약 응답으로
바꾸고, 공개 범위를 붙이고, status 를 정한다. 프롬프트·시각 변환은 `schedule_extractor` 다.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, FastAPI

from schedule_extractor import ExtractedSchedule, ProviderError, extract_schedules

from ..deps import make_api_key_guard, request_id
from ..errors import InternalError
from ..schemas import (ExtractedScheduleOut, ScheduleExtractionRequest,
                       ScheduleExtractionResponse)

logger = logging.getLogger("llmwiki.api")

_FAILED = "SCHEDULE_EXTRACTION_FAILED"


def _rfc3339(value: datetime) -> str:
    """RFC 3339 UTC. 마이크로초는 있을 때만 적는다 — 종일 일정의 종료가 그렇다.

    `Instant.parse` 가 둘 다 받는다 (RestClientAiClient:199).
    """
    utc = value.astimezone(timezone.utc)
    base = utc.strftime("%Y-%m-%dT%H:%M:%S")
    fraction = f".{utc.microsecond:06d}" if utc.microsecond else ""
    return f"{base}{fraction}Z"


def _to_contract(schedule: ExtractedSchedule,
                 payload: ScheduleExtractionRequest) -> ExtractedScheduleOut:
    """공개 범위는 요청값으로 채운다.

    백엔드가 응답의 이 값을 안 믿고 요청값을 쓰지만(`ScheduleSourceService.toDrafts`)
    검증은 비어 있으면 거부한다(`RestClientAiClient:196`). 추출기가 공개 범위를
    아예 모르게 두면 모델이 그것을 바꿀 여지가 없다.
    """
    return ExtractedScheduleOut(
        order=schedule.order,
        title=schedule.title,
        content=schedule.content,
        targetText=schedule.target_text,
        location=schedule.location,
        visibilityType=payload.visibilityType,
        departmentIds=list(payload.departmentIds),
        startAt=_rfc3339(schedule.start_at),
        endAt=_rfc3339(schedule.end_at),
    )


def build_router(app: FastAPI) -> APIRouter:
    guard = make_api_key_guard(app.state.api_key)
    router = APIRouter(prefix="/internal/v1", dependencies=[Depends(guard)])

    @router.post("/schedule-extractions", response_model=ScheduleExtractionResponse)
    async def extract(payload: ScheduleExtractionRequest,
                      rid: str = Depends(request_id)) -> ScheduleExtractionResponse:
        del rid  # 추적 ID 는 미들웨어가 헤더로 붙인다.

        try:
            result = await extract_schedules(
                payload.parsedMarkdown,
                now=datetime.now(timezone.utc),
                provider=app.state.schedule_provider)
        except ProviderError as error:
            # 예외 문자열에 URL·모델명·응답 조각이 들어갈 수 있다. 고정 메시지로 바꾸고
            # 상세는 로그에만 남긴다 (설계 §3.6).
            logger.exception("일정 추출 실패: %s", error)
            raise InternalError(_FAILED, "일정 추출에 실패했습니다.", status=500) from error

        return ScheduleExtractionResponse(
            status="extracted" if result.schedules else "no_schedule",
            schedules=[_to_contract(item, payload) for item in result.schedules],
            warnings=list(result.warnings),
        )

    return router
```

Task 5 가 status 3분기와 교차 검증을 이 자리에 넣는다. 지금은 두 갈래다.

- [ ] **Step 7: 임시 `normalize`·`prompt` 를 둔다**

Task 2·3 이 채운다. 지금은 껍데기 테스트를 통과할 최소값만 둔다.

`src/schedule_extractor/prompt.py`:

```python
"""모델에게 줄 프롬프트와 출력 스키마. Task 2 가 채운다."""

from __future__ import annotations

from datetime import datetime

OUTPUT_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "schedules": {"type": "array", "items": {"type": "object"}},
        "warnings": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["schedules", "warnings"],
}


def build_prompt(markdown: str, *, now: datetime) -> str:
    return markdown
```

`src/schedule_extractor/normalize.py`:

```python
"""모델 출력을 계약 자료형으로. Task 3 이 채운다."""

from __future__ import annotations

from datetime import datetime

from . import ExtractedSchedule


def normalize(raw: dict, *,
              now: datetime) -> tuple[tuple[ExtractedSchedule, ...], tuple[str, ...]]:
    return (), tuple(raw.get("warnings") or ())
```

- [ ] **Step 8: 앱에 배선한다**

`src/wiki_api/app.py` 를 고친다. `create_app` 이 provider 를 만들어 `app.state` 에 둔다 — `app.state.api_key` 와 같은 방식이고, 테스트가 그 속성을 교체한다.

```python
    from .routers import schedule, source_parse, wiki

    app.state.schedule_provider = None  # Task 7 이 설정에서 만든 어댑터로 채운다

    app.include_router(wiki.build_router(app))
    app.include_router(source_parse.build_router(app))
    app.include_router(schedule.build_router(app))
    return app
```

`app.py` 도입부 docstring 에 한 문장 더한다: "일정 추출은 `app.state.schedule_provider` 를 쓴다 — LLM 경계를 앱 조립 지점에서 교체할 수 있게 둔다."

- [ ] **Step 9: 테스트를 돌린다**

Run: `cd ai && uv run pytest tests/api/test_api_schedule.py -v`
Expected: 5개 PASS

Run: `cd ai && uv run pytest -m "not ocr and not llm" -q`
Expected: 기존 314개 + 6개 전부 PASS

- [ ] **Step 10: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106-79-schedule
git status --short
git add ai/src/schedule_extractor ai/src/wiki_api/schemas.py ai/src/wiki_api/errors.py \
        ai/src/wiki_api/app.py ai/src/wiki_api/routers/schedule.py \
        ai/tests/schedule/__init__.py ai/tests/api/test_api_schedule.py
git commit -m "feat(ai): 일정 추출 엔드포인트 경계를 만든다

백엔드가 부르는 /internal/v1/schedule-extractions 가 없어 404 였다.
계약 요청·응답 스키마와 오류 코드를 먼저 잠그고 추출은 뒤 작업에서 채운다.

Refs: S15P11B106-79"
```

---

### Task 2: 프롬프트와 출력 스키마

**Files:**
- Modify: `src/schedule_extractor/prompt.py`
- Create: `tests/schedule/test_prompt.py`

**Interfaces:**
- Consumes: 없음
- Produces: `OUTPUT_SCHEMA: dict`, `build_prompt(markdown: str, *, now: datetime) -> str`. 모델 출력 항목의 키는 `title`·`content`·`targetText`·`location`·`startLocal`·`endLocal`·`allDay` 다 — Task 3 의 `normalize` 가 이 이름을 읽는다.

출력 스키마에 **`status` 필드를 두지 않는다.** 측정에서 모델이 일정 2건을 내면서 `no_schedule` 이라 답했다 (설계 §2.1·§6.3). 시각은 naive 지역 시각이다 — 변환은 Task 3.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/schedule/test_prompt.py`:

```python
"""프롬프트가 규칙을 담고 있나. 모델을 부르지 않는다.

프롬프트 문장을 문자열로 검사하는 것은 취약하지만, 여기서 지키려는 것은 문장이 아니라
**규칙의 존재**다. 시각 변환 금지·기준 연도·종일 규칙이 빠지면 모델이 §2.1 의 실패로
되돌아간다.
"""

from datetime import datetime, timezone

from schedule_extractor.prompt import OUTPUT_SCHEMA, build_prompt

NOW = datetime(2026, 7, 29, 12, 0, tzinfo=timezone.utc)


def test_output_schema_has_no_status_field():
    """status 는 코드가 정한다 (설계 §6.3)."""
    assert "status" not in OUTPUT_SCHEMA["properties"]
    assert set(OUTPUT_SCHEMA["required"]) == {"schedules", "warnings"}


def test_output_schema_asks_for_local_naive_time():
    item = OUTPUT_SCHEMA["properties"]["schedules"]["items"]
    assert set(item["required"]) == {"title", "startLocal", "endLocal", "allDay"}
    for key in ("title", "content", "targetText", "location",
                "startLocal", "endLocal", "allDay"):
        assert key in item["properties"], key
    assert "startAt" not in item["properties"]


def test_prompt_carries_the_document():
    prompt = build_prompt("# 8월 일정\n- 8월 3일 회의\n", now=NOW)
    assert "# 8월 일정" in prompt
    assert "8월 3일 회의" in prompt


def test_prompt_states_the_reference_year_from_now():
    assert "2026" in build_prompt("문서", now=NOW)


def test_prompt_forbids_timezone_arithmetic():
    prompt = build_prompt("문서", now=NOW)
    assert "변환" in prompt
    assert "YYYY-MM-DDTHH:MM" in prompt


def test_prompt_states_the_all_day_rule():
    prompt = build_prompt("문서", now=NOW)
    assert "allDay" in prompt
    assert "00:00" in prompt


def test_prompt_reference_year_follows_kst_not_utc():
    """UTC 12월 31일 15:30 은 KST 로 다음 해 1월 1일 0시 30분이다."""
    prompt = build_prompt("문서", now=datetime(2026, 12, 31, 15, 30, tzinfo=timezone.utc))
    assert "2027" in prompt
    assert "2026" not in prompt
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/schedule/test_prompt.py -v`
Expected: `test_output_schema_asks_for_local_naive_time` 외 여러 개 FAIL

- [ ] **Step 3: 구현한다**

`src/schedule_extractor/prompt.py` 를 통째로 바꾼다:

```python
"""모델에게 줄 프롬프트와 출력 스키마.

**모델에게 시간 산술을 시키지 않는다.** 측정에서 `09:00` KST 를 `09:00Z` 로 냈다
(설계 §2.1). 모델은 문서에 적힌 지역 시각을 `YYYY-MM-DDTHH:MM` 으로 옮기고,
UTC 변환은 `normalize.py` 가 한다.

**출력 스키마에 `status` 를 두지 않는다.** 측정에서 모델이 일정 2건을 내면서
`no_schedule` 이라 답했다. 코드가 건수로 정하면 그 모순이 불가능해진다 (§6.3).
"""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")

OUTPUT_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "schedules": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "content": {"type": "string"},
                    "targetText": {"type": "string"},
                    "location": {"type": "string"},
                    "startLocal": {"type": "string"},
                    "endLocal": {"type": "string"},
                    "allDay": {"type": "boolean"},
                },
                "required": ["title", "startLocal", "endLocal", "allDay"],
            },
        },
        "warnings": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["schedules", "warnings"],
}

_TEMPLATE = """사내 일정 문서에서 개별 일정을 모두 추출한다.

시각 규칙 — 계산하지 않는다:
- `startLocal`·`endLocal` 은 문서에 적힌 시각을 그대로 `YYYY-MM-DDTHH:MM` 으로 쓴다.
  시간대 변환을 하지 않는다.
- 기준 연도는 {year}년이다. 연도가 없으면 {year}을 쓴다.
- 시각이 없고 날짜만 있으면 `allDay` 를 true 로 하고 `startLocal` 은 `그날T00:00`,
  `endLocal` 은 `그날T23:59` 로 쓴다.
- 시작 시각은 있고 종료 시각이 없으면 문맥으로 추정하고 warnings 에 남긴다.

추출 규칙:
- 날짜가 있는 항목은 전부 일정이다. 급여 지급일, 신청 마감일도 일정이다.
- 문의처·연락처처럼 날짜가 없는 것은 일정이 아니다.
- `title` 은 그 일정을 가리키는 이름이다.
- `content` 는 일정의 설명, `targetText` 는 문서가 적은 대상, `location` 은 장소다.
  문서에 적혀 있으면 반드시 옮긴다. 없으면 비운다.
- 불명확한 날짜·시각·대상은 warnings 에 한국어로 남긴다.

문서:
{document}
"""


def build_prompt(markdown: str, *, now: datetime) -> str:
    """기준 연도를 KST 로 본다.

    UTC 자정 전후 9시간은 두 시간대의 날짜가 다르고, 12월 31일 밤에는 연도까지
    다르다. 문서를 쓴 사람의 연도가 기준이다 (설계 §3.5).
    """
    return _TEMPLATE.format(year=now.astimezone(KST).year, document=markdown)
```

- [ ] **Step 4: 테스트를 돌린다**

Run: `cd ai && uv run pytest tests/schedule/test_prompt.py -v`
Expected: 7개 PASS

- [ ] **Step 5: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106-79-schedule
git status --short
git add ai/src/schedule_extractor/prompt.py ai/tests/schedule/test_prompt.py
git commit -m "feat(ai): 일정 추출 프롬프트와 출력 스키마를 정한다

모델은 문서에 적힌 지역 시각만 옮기고 시간대 변환을 하지 않는다.
출력 스키마에 status 를 두지 않아 모델이 건수와 상태를 어긋나게 낼 수 없다.

Refs: S15P11B106-79"
```

---

### Task 3: 정규화 — 시각·연도·탈락·누락

**Files:**
- Modify: `src/schedule_extractor/normalize.py`
- Create: `tests/schedule/test_normalize.py`

**Interfaces:**
- Consumes: `ExtractedSchedule` (Task 1), 모델 출력 키 이름 (Task 2)
- Produces: `normalize(raw: dict, *, now: datetime) -> tuple[tuple[ExtractedSchedule, ...], tuple[str, ...]]`, 상수 `KST`·`ALL_DAY_END`

이 파일이 계획에서 가장 크다. LLM 없이 도는 순수 함수이고, 설계의 시각 규칙 전부가 여기 있다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/schedule/test_normalize.py`:

```python
"""정규화 — 모델 출력을 계약 자료형으로.

LLM 을 부르지 않는다. 설계 §3.5 의 시각 규칙과 §3.3 의 탈락·경고 규칙이 전부 여기 있다.
"""

from datetime import datetime, timezone

import pytest

from schedule_extractor.normalize import normalize

NOW = datetime(2026, 7, 29, 3, 0, tzinfo=timezone.utc)  # KST 12:00


def _raw(**overrides):
    item = {"title": "하계 워크샵", "content": "전사 워크샵",
            "targetText": "개발부", "location": "속초",
            "startLocal": "2026-08-12T09:00", "endLocal": "2026-08-13T18:00",
            "allDay": False}
    item.update(overrides)
    return {"schedules": [item], "warnings": []}


def test_local_time_becomes_utc():
    """09:00 KST = 00:00Z. 모델에게 시키면 09:00Z 로 틀린다 (설계 §2.1)."""
    schedules, _ = normalize(_raw(), now=NOW)
    assert schedules[0].start_at == datetime(2026, 8, 12, 0, 0, tzinfo=timezone.utc)
    assert schedules[0].end_at == datetime(2026, 8, 13, 9, 0, tzinfo=timezone.utc)


def test_midnight_crosses_to_the_previous_utc_day():
    schedules, _ = normalize(
        _raw(startLocal="2026-08-12T00:00", endLocal="2026-08-12T08:00"), now=NOW)
    assert schedules[0].start_at == datetime(2026, 8, 11, 15, 0, tzinfo=timezone.utc)


def test_all_day_end_keeps_the_last_second():
    """23:59 은 하루의 마지막 60초를 잘라낸다. DATETIME(6) 이라 마이크로초를 담는다."""
    schedules, _ = normalize(
        _raw(startLocal="2026-08-25T00:00", endLocal="2026-08-25T23:59", allDay=True),
        now=NOW)
    assert schedules[0].end_at == datetime(
        2026, 8, 25, 14, 59, 59, 999999, tzinfo=timezone.utc)


def test_missing_end_gets_one_hour_and_a_warning():
    schedules, warnings = normalize(_raw(endLocal=""), now=NOW)
    assert schedules[0].end_at == datetime(2026, 8, 12, 1, 0, tzinfo=timezone.utc)
    assert any("종료" in warning for warning in warnings)


def test_all_day_without_end_fills_the_whole_day():
    """모델이 allDay 만 내고 endLocal 을 비우는 경우. 1시간이 아니라 그날 전체다."""
    schedules, warnings = normalize(
        _raw(startLocal="2026-08-25T00:00", endLocal="", allDay=True), now=NOW)
    assert schedules[0].end_at == datetime(
        2026, 8, 25, 14, 59, 59, 999999, tzinfo=timezone.utc)
    assert any("종일" in warning for warning in warnings)


def test_year_missing_uses_the_reference_year():
    schedules, warnings = normalize(
        _raw(startLocal="08-30T10:00", endLocal="08-30T11:00"), now=NOW)
    assert schedules[0].start_at.year == 2026
    assert any("연도" in warning for warning in warnings)


def test_year_inference_rolls_forward_when_too_far_past():
    """12월 문서의 1월 5일에 현재 연도를 넣으면 11개월 과거가 된다."""
    december = datetime(2026, 12, 20, 3, 0, tzinfo=timezone.utc)
    schedules, warnings = normalize(
        _raw(startLocal="01-05T10:00", endLocal="01-05T11:00"), now=december)
    assert schedules[0].start_at.year == 2027
    assert any("연도" in warning for warning in warnings)


def test_reference_year_follows_kst():
    """UTC 12월 31일 15:30 은 KST 로 2027년 1월 1일 0시 30분이다."""
    turn = datetime(2026, 12, 31, 15, 30, tzinfo=timezone.utc)
    schedules, _ = normalize(
        _raw(startLocal="01-05T10:00", endLocal="01-05T11:00"), now=turn)
    assert schedules[0].start_at.year == 2027


def test_reversed_period_drops_only_that_item():
    raw = _raw()
    raw["schedules"].append({"title": "뒤집힌 일정",
                             "startLocal": "2026-08-20T16:00",
                             "endLocal": "2026-08-20T14:00", "allDay": False})
    schedules, warnings = normalize(raw, now=NOW)
    assert [item.title for item in schedules] == ["하계 워크샵"]
    assert any("뒤집힌 일정" in warning for warning in warnings)


def test_blank_title_drops_the_item():
    schedules, warnings = normalize(_raw(title="   "), now=NOW)
    assert schedules == ()
    assert any("제목" in warning for warning in warnings)


def test_unparseable_date_drops_the_item():
    schedules, warnings = normalize(_raw(startLocal="언젠가"), now=NOW)
    assert schedules == ()
    assert any("언젠가" in warning for warning in warnings)


def test_missing_content_and_target_are_warnings_not_drops():
    """FR-SCH-001 이 내용·대상 추출을 요구한다. 버리는 것이 더 어긋난다 (설계 §3.3)."""
    schedules, warnings = normalize(_raw(content="", targetText=""), now=NOW)
    assert len(schedules) == 1
    assert schedules[0].content is None
    assert schedules[0].target_text is None
    assert any("내용" in warning for warning in warnings)
    assert any("대상" in warning for warning in warnings)


def test_missing_location_is_silent():
    """장소는 FR-SCH-001 의 요구 대상이 아니다."""
    _, warnings = normalize(_raw(location=""), now=NOW)
    assert not any("장소" in warning for warning in warnings)


def test_order_is_renumbered_from_one():
    raw = _raw()
    raw["schedules"] = [
        {"title": "셋째", "startLocal": "2026-08-03T10:00",
         "endLocal": "2026-08-03T11:00", "allDay": False},
        {"title": "버려질 것", "startLocal": "깨진 값",
         "endLocal": "2026-08-04T11:00", "allDay": False},
        {"title": "둘째", "startLocal": "2026-08-05T10:00",
         "endLocal": "2026-08-05T11:00", "allDay": False},
    ]
    schedules, _ = normalize(raw, now=NOW)
    assert [item.order for item in schedules] == [1, 2]
    assert [item.title for item in schedules] == ["셋째", "둘째"]


def test_model_warnings_are_kept():
    raw = _raw()
    raw["warnings"] = ["안전교육 종료 시각을 추정했습니다."]
    _, warnings = normalize(raw, now=NOW)
    assert "안전교육 종료 시각을 추정했습니다." in warnings


def test_empty_input_yields_nothing():
    schedules, warnings = normalize({"schedules": [], "warnings": []}, now=NOW)
    assert schedules == ()
    assert warnings == ()


@pytest.mark.parametrize("payload", [{}, {"schedules": None, "warnings": None}])
def test_missing_keys_do_not_crash(payload):
    """모델이 스키마를 어겨도 500 은 라우터가 낸다 — 여기서 터지면 안 된다."""
    schedules, warnings = normalize(payload, now=NOW)
    assert schedules == ()
    assert warnings == ()
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/schedule/test_normalize.py -v`
Expected: 대부분 FAIL — 껍데기 `normalize` 가 빈 튜플만 낸다

- [ ] **Step 3: 구현한다**

`src/schedule_extractor/normalize.py` 를 통째로 바꾼다:

```python
"""모델 출력을 계약 자료형으로. LLM 을 부르지 않는다.

**여기가 계산을 맡는다** — 모델에게 시간 산술을 시키면 틀린다 (설계 §2.1).
KST→UTC 변환·연도 추론·순서 부여·탈락 판정·누락 경고가 전부 여기 있다.

탈락은 그 항목만 버린다. 문서 전체를 실패로 만들지 않는다 — 관리자가 초안을 건별로
검토·수정·승인하므로(FR-SCH-002·003) 4건을 살려 보내는 것이 5건을 버리는 것보다 낫다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from . import ExtractedSchedule

KST = ZoneInfo("Asia/Seoul")

ROLL_FORWARD_MARGIN = timedelta(days=90)
"""추론한 연도로 계산한 날짜가 기준일보다 이만큼 과거면 다음 해로 본다.

12월 문서의 `1월 5일` 에 현재 연도를 넣으면 11개월 과거가 된다 (설계 §3.5).
"""

DEFAULT_DURATION = timedelta(hours=1)
"""시작 시각은 있고 종료가 없을 때 채우는 길이."""


def normalize(raw: dict, *,
              now: datetime) -> tuple[tuple[ExtractedSchedule, ...], tuple[str, ...]]:
    reference = now.astimezone(KST)
    warnings: list[str] = list(raw.get("warnings") or ())
    kept: list[ExtractedSchedule] = []

    for item in raw.get("schedules") or ():
        schedule = _one(item, reference=reference, warnings=warnings)
        if schedule is not None:
            kept.append(schedule)

    renumbered = tuple(
        ExtractedSchedule(order=index, title=schedule.title, content=schedule.content,
                          target_text=schedule.target_text, location=schedule.location,
                          start_at=schedule.start_at, end_at=schedule.end_at)
        for index, schedule in enumerate(kept, start=1))
    return renumbered, tuple(warnings)


def _one(item: dict, *, reference: datetime,
         warnings: list[str]) -> ExtractedSchedule | None:
    """일정 1건. 살릴 수 없으면 `None` 과 경고 한 줄."""
    title = _clean(item.get("title"))
    if title is None:
        warnings.append("제목이 없는 항목을 버렸습니다.")
        return None

    start = _parse_local(item.get("startLocal"), reference=reference, warnings=warnings,
                         title=title)
    if start is None:
        return None

    all_day = bool(item.get("allDay"))
    end = _parse_local(item.get("endLocal"), reference=reference, warnings=warnings,
                       title=title, quiet=True)
    if end is None:
        # 종일을 먼저 본다. 이 순서가 아니면 종일 일정이 1시간짜리가 된다 —
        # 모델이 allDay 만 내고 endLocal 을 비우는 경우가 그 경로다.
        if all_day:
            end = start.replace(hour=23, minute=59, second=59, microsecond=999_999)
            warnings.append(f"'{title}'이(가) 종일 일정인데 종료 시각이 없어 그날 끝으로 두었습니다.")
        else:
            end = start + DEFAULT_DURATION
            warnings.append(f"'{title}'의 종료 시각이 없어 시작 후 1시간으로 두었습니다.")
    elif all_day:
        end = end.replace(hour=23, minute=59, second=59, microsecond=999_999)

    if end < start:
        warnings.append(f"'{title}'의 종료가 시작보다 앞서 버렸습니다.")
        return None

    content = _clean(item.get("content"))
    if content is None:
        warnings.append(f"'{title}'의 내용을 문서에서 찾지 못했습니다.")
    target_text = _clean(item.get("targetText"))
    if target_text is None:
        warnings.append(f"'{title}'의 대상을 문서에서 찾지 못했습니다.")

    return ExtractedSchedule(
        order=0,  # normalize 가 마지막에 1..N 으로 다시 매긴다
        title=title, content=content, target_text=target_text,
        location=_clean(item.get("location")),
        start_at=start.astimezone(timezone.utc),
        end_at=end.astimezone(timezone.utc))


def _clean(value) -> str | None:
    """빈 문자열을 `None` 으로. 모델이 `targetText: ""` 를 낸 것을 측정에서 봤다."""
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def _parse_local(value, *, reference: datetime, warnings: list[str], title: str,
                 quiet: bool = False) -> datetime | None:
    """모델이 낸 naive 지역 시각을 KST tz-aware 로.

    연도가 없는 형태(`MM-DDTHH:MM`)도 받는다 — 모델이 문서를 그대로 옮기다 연도를
    빠뜨리는 경우가 있다. 그때 기준 연도를 넣고 경고를 남긴다.
    """
    text = _clean(value)
    if text is None:
        if not quiet:
            warnings.append(f"'{title}'의 시각이 비어 있어 버렸습니다.")
        return None

    naive = _parse_naive(text)
    if naive is not None:
        return naive.replace(tzinfo=KST)

    inferred = _parse_without_year(text, reference=reference)
    if inferred is not None:
        warnings.append(f"'{title}'의 연도가 없어 {inferred.year}년으로 보았습니다.")
        return inferred

    if not quiet:
        warnings.append(f"'{title}'의 시각을 읽지 못해 버렸습니다: {text}")
    return None


def _parse_naive(text: str) -> datetime | None:
    for pattern in ("%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(text, pattern)
        except ValueError:
            continue
    return None


def _parse_without_year(text: str, *, reference: datetime) -> datetime | None:
    """`MM-DDTHH:MM` 에 기준 연도를 넣는다. 너무 과거면 다음 해로 본다."""
    for pattern in ("%m-%dT%H:%M", "%m-%d"):
        try:
            partial = datetime.strptime(text, pattern)
        except ValueError:
            continue
        candidate = partial.replace(year=reference.year, tzinfo=KST)
        if reference - candidate > ROLL_FORWARD_MARGIN:
            candidate = candidate.replace(year=reference.year + 1)
        return candidate
    return None
```

- [ ] **Step 4: 테스트를 돌린다**

Run: `cd ai && uv run pytest tests/schedule/test_normalize.py -v`
Expected: 18개 PASS

Run: `cd ai && uv run pytest -m "not ocr and not llm" -q`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106-79-schedule
git status --short
git add ai/src/schedule_extractor/normalize.py ai/tests/schedule/test_normalize.py
git commit -m "feat(ai): 일정 시각 변환과 탈락 판정을 코드로 옮긴다

KST 지역 시각을 UTC 로 바꾸고 연도 없는 날짜에 기준 연도를 넣는다.
탈락은 그 항목만 버리고 경고를 남긴다 — 문서 전체를 실패로 만들지 않는다.

Refs: S15P11B106-79"
```

---

### Task 4: 날짜 패턴 검사

**Files:**
- Create: `src/schedule_extractor/date_probe.py`
- Create: `tests/schedule/test_date_probe.py`

**Interfaces:**
- Consumes: 없음
- Produces: `has_date_pattern(markdown: str) -> bool`

**왜 필요한가** — `gemma4:e2b` 가 일정 3건짜리 문서에 0건을 냈다 (설계 §2.1). 모델 출력만 믿으면 그것이 `no_schedule` 로 나가고 관리자는 "이 문서에 일정이 없다" 는 답을 받아 재시도할 이유를 못 얻는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`tests/schedule/test_date_probe.py`:

```python
"""문서에 날짜 패턴이 있나. 모델이 0건을 냈을 때만 돈다 (설계 §3.3).

거짓 양성은 감수한다 — 회의록의 작성일처럼 일정이 아닌 날짜가 500 을 만든다.
일정이 든 문서를 "없다" 고 답하는 쪽이 더 나쁘다.
"""

import pytest

from schedule_extractor.date_probe import has_date_pattern


@pytest.mark.parametrize("text", [
    "워크샵은 8월 12일에 진행합니다.",
    "일시: 2026-08-12",
    "기간 2026.08.12 ~ 2026.08.13",
    "제출 기한은 8/5 입니다.",
    "8월 20일(목) 오후 2시",
    "12월 31일까지",
])
def test_finds_korean_and_numeric_dates(text):
    assert has_date_pattern(text) is True


@pytest.mark.parametrize("text", [
    "문의: 인사팀 내선 1234",
    "담당자 김철수, 연락처 010-1234-5678",
    "",
    "회의실 예약은 3층 안내데스크에서 받습니다.",
    "예산은 1,200,000원입니다.",
])
def test_ignores_documents_without_dates(text):
    assert has_date_pattern(text) is False


def test_phone_number_is_not_a_date():
    """010-1234-5678 을 날짜로 읽으면 모든 문서가 날짜를 가진 것이 된다."""
    assert has_date_pattern("연락처 010-1234-5678") is False


def test_amount_with_slash_is_not_a_date():
    assert has_date_pattern("단가 30/개") is False
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/schedule/test_date_probe.py -v`
Expected: `ModuleNotFoundError: No module named 'schedule_extractor.date_probe'`

- [ ] **Step 3: 구현한다**

`src/schedule_extractor/date_probe.py`:

```python
"""문서에 날짜가 있나 — 모델의 0건 응답을 검증하는 독립 근거.

`gemma4:e2b` 가 일정 3건짜리 문서에 0건을 냈다 (설계 §2.1). 모델 출력만 믿으면
그것이 `no_schedule` 로 나가고, 관리자는 "일정이 없다" 는 답을 받아 재시도하지 않는다.

**거짓 양성을 감수한다.** 회의록의 작성일, 규정의 시행일처럼 일정이 아닌 날짜가
거짓 500 을 만든다. 일정이 든 문서를 "없다" 고 답하는 쪽이 더 나쁘다 (설계 §3.3).

전화번호(`010-1234-5678`)와 수량(`30/개`)을 날짜로 읽지 않는 것이 정확도의 핵심이다 —
그것들이 통과하면 거의 모든 문서가 날짜를 가진 것이 되어 검사가 무의미해진다.
"""

from __future__ import annotations

import re

_PATTERNS = (
    # 8월 12일 · 12월 31일
    re.compile(r"\d{1,2}\s*월\s*\d{1,2}\s*일"),
    # 2026-08-12 · 2026.08.12 · 2026/08/12
    re.compile(r"(?<!\d)\d{4}\s*[-./]\s*\d{1,2}\s*[-./]\s*\d{1,2}(?!\d)"),
    # 8/5 · 08-12 — 앞뒤에 숫자나 하이픈이 붙지 않은 것만. 전화번호를 걸러낸다
    re.compile(r"(?<![\d\-/])\d{1,2}\s*[-/]\s*\d{1,2}(?![\d\-/])\s*(?=[)\s(일월,.]|$)"),
)


def has_date_pattern(markdown: str) -> bool:
    return any(pattern.search(markdown) for pattern in _PATTERNS)
```

- [ ] **Step 4: 테스트를 돌린다**

Run: `cd ai && uv run pytest tests/schedule/test_date_probe.py -v`
Expected: 12개 PASS

정규식은 한 번에 맞기 어렵다. FAIL 하면 **테스트를 고치지 말고 정규식을 고친다** — 테스트가 요구하는 것이 설계다. 특히 `30/개` 와 `8/5` 를 가르는 것은 뒤따르는 문자다.

- [ ] **Step 5: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106-79-schedule
git status --short
git add ai/src/schedule_extractor/date_probe.py ai/tests/schedule/test_date_probe.py
git commit -m "feat(ai): 문서 날짜 패턴 검사를 더한다

모델이 0건을 냈을 때 그것이 진짜 일정 없음인지 확인할 독립 근거가 필요하다.
작은 모델이 일정 3건짜리 문서에 0건을 낸 것을 측정에서 봤다.

Refs: S15P11B106-79"
```

---

### Task 5: 라우터 통합 — status 3분기·교차 검증

**Files:**
- Modify: `src/wiki_api/routers/schedule.py`
- Modify: `tests/api/test_api_schedule.py`

**Interfaces:**
- Consumes: `Extraction.raw_count` (Task 1), `has_date_pattern` (Task 4), `normalize` (Task 3)
- Produces: 없음 — 이 Task 로 엔드포인트가 완성된다

- [ ] **Step 1: 실패하는 테스트를 더한다**

`tests/api/test_api_schedule.py` 끝에 붙인다:

```python
ONE_ITEM = {
    "schedules": [{"title": "하계 워크샵", "content": "전사 워크샵",
                   "targetText": "개발부 전 직원", "location": "속초",
                   "startLocal": "2026-08-12T09:00",
                   "endLocal": "2026-08-13T18:00", "allDay": False}],
    "warnings": [],
}


def _post(provider, request=None):
    return _client(provider).post(
        "/internal/v1/schedule-extractions", json=request or REQUEST,
        headers={"X-Internal-Api-Key": API_KEY})


def test_extracted_response_matches_the_contract():
    body = _post(FakeProvider(ONE_ITEM)).json()
    assert body["status"] == "extracted"
    assert body["warnings"] == []
    schedule = body["schedules"][0]
    assert schedule == {
        "order": 1,
        "title": "하계 워크샵",
        "content": "전사 워크샵",
        "targetText": "개발부 전 직원",
        "location": "속초",
        "visibilityType": "department",
        "departmentIds": ["1", "2"],
        "startAt": "2026-08-12T00:00:00Z",
        "endAt": "2026-08-13T09:00:00Z",
    }


def test_visibility_is_echoed_from_the_request():
    """백엔드가 응답 값을 안 믿지만 비어 있으면 거부한다 (RestClientAiClient:196)."""
    request = {**REQUEST, "visibilityType": "all", "departmentIds": []}
    schedule = _post(FakeProvider(ONE_ITEM), request).json()["schedules"][0]
    assert schedule["visibilityType"] == "all"
    assert schedule["departmentIds"] == []


def test_all_day_end_keeps_microseconds_in_the_response():
    payload = {"schedules": [{"title": "급여 지급",
                              "startLocal": "2026-08-25T00:00",
                              "endLocal": "2026-08-25T23:59", "allDay": True}],
               "warnings": []}
    schedule = _post(FakeProvider(payload)).json()["schedules"][0]
    assert schedule["startAt"] == "2026-08-24T15:00:00Z"
    assert schedule["endAt"] == "2026-08-25T14:59:59.999999Z"


def test_zero_items_with_dates_in_the_document_is_500():
    """모델이 흘렸을 가능성이 있다 — no_schedule 로 내면 관리자가 재시도하지 않는다."""
    response = _post(FakeProvider({"schedules": [], "warnings": []}))
    assert response.status_code == 500
    assert response.json()["code"] == "SCHEDULE_EXTRACTION_FAILED"


def test_all_items_dropped_is_500():
    payload = {"schedules": [{"title": "뒤집힌 일정",
                              "startLocal": "2026-08-20T16:00",
                              "endLocal": "2026-08-20T14:00", "allDay": False}],
               "warnings": []}
    response = _post(FakeProvider(payload))
    assert response.status_code == 500
    assert response.json()["code"] == "SCHEDULE_EXTRACTION_FAILED"


def test_partial_failure_keeps_the_survivors():
    payload = {"schedules": [
        ONE_ITEM["schedules"][0],
        {"title": "뒤집힌 일정", "startLocal": "2026-08-20T16:00",
         "endLocal": "2026-08-20T14:00", "allDay": False},
    ], "warnings": []}
    body = _post(FakeProvider(payload)).json()
    assert body["status"] == "extracted"
    assert [item["title"] for item in body["schedules"]] == ["하계 워크샵"]
    assert any("뒤집힌 일정" in warning for warning in body["warnings"])


def test_provider_error_does_not_leak_internals():
    """errors.py:141 의 마지막 그물은 예외 문자열을 응답에 싣는다 (설계 §3.6)."""
    from schedule_extractor import ProviderError

    secret = "http://localhost:11434 model=qwen2.5 token=abcd"
    response = _post(FakeProvider(error=ProviderError(secret)))
    assert response.status_code == 500
    assert response.json()["code"] == "SCHEDULE_EXTRACTION_FAILED"
    assert secret not in response.text
    assert "11434" not in response.text


def test_unexpected_provider_exception_is_also_500_without_details():
    secret = "sk-ant-api03-do-not-leak"
    response = _post(FakeProvider(error=RuntimeError(secret)))
    assert response.status_code == 500
    assert response.json()["code"] == "SCHEDULE_EXTRACTION_FAILED"
    assert secret not in response.text


def test_order_is_sequential_across_many_items():
    payload = {"schedules": [
        {"title": f"일정 {index}", "startLocal": f"2026-08-{index:02d}T10:00",
         "endLocal": f"2026-08-{index:02d}T11:00", "allDay": False}
        for index in range(1, 6)
    ], "warnings": []}
    body = _post(FakeProvider(payload)).json()
    assert [item["order"] for item in body["schedules"]] == [1, 2, 3, 4, 5]
```

`test_empty_document_returns_no_schedule` 은 그대로 통과해야 한다 — 그 문서(`# 문의처\n인사팀 내선 1234`)에는 날짜가 없다.

- [ ] **Step 2: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/api/test_api_schedule.py -v`
Expected: `test_zero_items_with_dates_in_the_document_is_500`·`test_all_items_dropped_is_500`·`test_unexpected_provider_exception_is_also_500_without_details` FAIL

- [ ] **Step 3: status 3분기와 교차 검증을 넣는다**

`src/wiki_api/routers/schedule.py` 의 `extract` 를 이렇게 바꾼다:

```python
    @router.post("/schedule-extractions", response_model=ScheduleExtractionResponse)
    async def extract(payload: ScheduleExtractionRequest,
                      rid: str = Depends(request_id)) -> ScheduleExtractionResponse:
        del rid  # 추적 ID 는 미들웨어가 헤더로 붙인다.

        try:
            result = await extract_schedules(
                payload.parsedMarkdown,
                now=datetime.now(timezone.utc),
                provider=app.state.schedule_provider)
        except Exception as error:  # noqa: BLE001 — 아래 주석
            # ProviderError 만 잡으면 어댑터가 흘린 httpx·json 예외가 errors.py:141 의
            # 마지막 그물로 가고, 그 그물은 예외 문자열을 응답에 싣는다. URL·모델명·API
            # 키 조각이 Spring 로그로 넘어갈 수 있다 (설계 §3.6).
            logger.exception("일정 추출 실패")
            raise InternalError(_FAILED, "일정 추출에 실패했습니다.", status=500) from error

        _reject_if_the_extraction_collapsed(result, payload.parsedMarkdown)

        schedules = [_to_contract(item, payload) for item in result.schedules]
        _assert_consistent(schedules)
        return ScheduleExtractionResponse(
            status="extracted" if schedules else "no_schedule",
            schedules=schedules,
            warnings=list(result.warnings),
        )
```

같은 파일에 헬퍼 둘을 더한다:

```python
def _reject_if_the_extraction_collapsed(result, markdown: str) -> None:
    """살아남은 일정이 0건일 때 `no_schedule` 과 실패를 가른다 (설계 §3.3).

    `no_schedule` 은 백엔드가 원본·파싱 파일을 지우고 관리자에게 "일정이 없다" 고
    답하는 신호다. 모델이 흘렸을 뿐인데 그렇게 답하면 관리자는 재시도할 이유를
    얻지 못한다.
    """
    if result.schedules:
        return
    if result.raw_count > 0:
        raise InternalError(_FAILED, "추출한 일정을 모두 사용할 수 없었습니다.", status=500)
    if has_date_pattern(markdown):
        raise InternalError(
            _FAILED, "문서에 날짜가 있으나 일정을 추출하지 못했습니다.", status=500)


def _assert_consistent(schedules: list[ExtractedScheduleOut]) -> None:
    """조립 결과를 스스로 본다.

    백엔드 검증(`RestClientAiClient:173`·`:192`)은 필드 형식만 본다 — `order` 중복·
    양수·연속을 확인하지 않는다. 여기서 어긋나면 조립 버그이므로 500 이다.
    """
    orders = [item.order for item in schedules]
    if orders != list(range(1, len(schedules) + 1)):
        raise InternalError(_FAILED, "일정 순서를 조립하지 못했습니다.", status=500)
```

임포트를 고친다. `except Exception` 이 되었으니 `ProviderError` 는 더 쓰지 않는다 — 안 쓰는 임포트를 남기지 않는다.

```python
from schedule_extractor import ExtractedSchedule, extract_schedules
from schedule_extractor.date_probe import has_date_pattern
```

`ExtractedScheduleOut` 도 `_assert_consistent` 의 형 힌트에 쓰므로 이미 임포트돼 있다.

- [ ] **Step 4: 테스트를 돌린다**

Run: `cd ai && uv run pytest tests/api/test_api_schedule.py -v`
Expected: 15개 PASS

Run: `cd ai && uv run pytest -m "not ocr and not llm" -q`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106-79-schedule
git status --short
git add ai/src/wiki_api/routers/schedule.py ai/tests/api/test_api_schedule.py
git commit -m "feat(ai): 일정 추출 상태 판정과 자기검증을 넣는다

모델 0건을 그대로 no_schedule 로 내지 않는다 — 문서에 날짜가 있으면 실패로 본다.
no_schedule 은 백엔드가 원본을 지우고 관리자에게 일정 없음을 알리는 신호다.

Refs: S15P11B106-79"
```

---

### Task 6: ollama 어댑터와 로컬 모델 테스트

**Files:**
- Create: `src/schedule_extractor/providers/__init__.py`
- Create: `src/schedule_extractor/providers/ollama.py`
- Create: `tests/schedule/test_local_model.py`
- Create: `experiments/corpus-schedule/README.md`
- Create: `experiments/corpus-schedule/documents/01-august-notice.md`
- Create: `experiments/corpus-schedule/documents/02-no-schedule.md`
- Create: `experiments/corpus-schedule/expected.json`
- Modify: `pyproject.toml` (`markers`)

**Interfaces:**
- Consumes: `JsonCompletionProvider` (Task 1)
- Produces: `OllamaProvider(model: str, base_url: str, timeout_seconds: float)` — `complete_json` 구현

- [ ] **Step 1: 마커를 등록한다**

`pyproject.toml` 의 `[tool.pytest.ini_options]` `markers` 에 한 줄 더한다:

```toml
markers = [
    "ocr: 로컬 Tesseract(eng traineddata) 필요",
    "llm: 로컬 ollama 와 모델 필요. 출력이 비결정적이라 기본 실행에서 뺀다",
]
```

- [ ] **Step 2: 어댑터 테스트를 쓴다 (모델 없이)**

`tests/schedule/test_local_model.py` 앞부분:

```python
"""로컬 모델 어댑터.

전송 계층은 모델 없이 검사한다 — httpx MockTransport 로 ollama 응답을 흉내낸다.
실제 모델 호출은 @pytest.mark.llm 이고 기본 실행에서 빠진다 (설계 §6.4).
"""

import json

import httpx
import pytest

from schedule_extractor import ProviderError
from schedule_extractor.providers.ollama import OllamaProvider

SCHEMA = {"type": "object", "properties": {"schedules": {"type": "array"}}}


def _provider(handler) -> OllamaProvider:
    transport = httpx.MockTransport(handler)
    return OllamaProvider(model="test-model", base_url="http://localhost:11434",
                          timeout_seconds=5.0, transport=transport)


async def test_parses_the_model_response():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["model"] == "test-model"
        assert body["stream"] is False
        assert body["format"] == SCHEMA
        assert body["options"]["temperature"] == 0
        return httpx.Response(200, json={"response": '{"schedules": []}'})

    result = await _provider(handler).complete_json("프롬프트", SCHEMA)
    assert result == {"schedules": []}


async def test_http_error_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="model not found")

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_non_json_response_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"response": "일정을 찾았습니다!"})

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_timeout_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("too slow", request=request)

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)
```

- [ ] **Step 3: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/schedule/test_local_model.py -v`
Expected: `ModuleNotFoundError: No module named 'schedule_extractor.providers'`

- [ ] **Step 4: 어댑터를 만든다**

`src/schedule_extractor/providers/__init__.py`:

```python
"""LLM 어댑터. 포트는 `..provider.JsonCompletionProvider` 다."""
```

`src/schedule_extractor/providers/ollama.py`:

```python
"""로컬 모델 어댑터 — 개발 확인과 품질 측정에 쓴다. 과금이 없다.

ollama 의 `format` 에 JSON Schema 를 그대로 넘기면 문법 제약으로 출력을 강제한다.
`temperature=0` 은 재현성을 위한 것이지 보장은 아니다 — 그래서 이 경로를 쓰는
테스트는 `@pytest.mark.llm` 이다 (설계 §6.4).

배포 어댑터는 `anthropic.py` 다. 둘의 유일한 접점은 `complete_json` 이다.
"""

from __future__ import annotations

import json

import httpx

from ..provider import ProviderError


class OllamaProvider:
    def __init__(self, *, model: str, base_url: str, timeout_seconds: float,
                 transport: httpx.BaseTransport | None = None):
        self._model = model
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport

    async def complete_json(self, prompt: str, schema: dict) -> dict:
        payload = {"model": self._model, "prompt": prompt, "format": schema,
                   "stream": False, "options": {"temperature": 0}}
        try:
            async with httpx.AsyncClient(timeout=self._timeout,
                                         transport=self._transport) as client:
                response = await client.post(f"{self._base_url}/api/generate",
                                             json=payload)
                response.raise_for_status()
                text = response.json()["response"]
        except (httpx.HTTPError, KeyError, ValueError) as error:
            raise ProviderError(f"ollama 호출 실패: {error}") from error

        try:
            return json.loads(text)
        except json.JSONDecodeError as error:
            raise ProviderError(f"모델이 JSON 을 내지 않았다: {text[:200]}") from error
```

- [ ] **Step 5: 코퍼스를 만든다**

`experiments/corpus-schedule/README.md`:

```markdown
# 일정 문서 코퍼스 (합성)

`@pytest.mark.llm` 테스트와 모델 대조가 쓰는 셋이다.

**합성 문서다.** 실제 사내 문서가 아니다. 사내 공지 형식을 흉내낸 것이며, 문서 크기·
어투는 실제와 다를 수 있다.

| 파일 | 무엇을 재나 |
| --- | --- |
| `documents/01-august-notice.md` | 시각 있는 일정 2건 · 종일 일정 1건 · 일정 아닌 항목 1건 |
| `documents/02-no-schedule.md` | 날짜가 없는 문서 — `no_schedule` 이 나와야 한다 |

`expected.json` 은 정답이다. 시각은 문서에 적힌 KST 지역 시각이 아니라 **변환 후 UTC** 다.

채점은 재현율·정밀도로 본다 — 몇 건 중 몇 건을 찾았나, 없는 일정을 만들었나, 시각이
맞나. 시각 변환이 코드로 빠져서 모델 채점이 결정적이다 (설계 §5).

영어 위키 코퍼스(`../corpus/`)의 수치와 직접 비교하지 않는다. 과제가 다르다.
```

`experiments/corpus-schedule/documents/01-august-notice.md`:

```markdown
# 2026년 8월 사내 일정 공지

## 1. 하계 워크샵
- 일시: 8월 12일(수) 09:00 ~ 8월 13일(목) 18:00
- 장소: 강원도 속초 리조트
- 대상: 개발부, 디자인부 전 직원
- 참가 신청은 8월 5일까지 인사팀으로 제출

## 2. 정기 안전교육
- 8월 20일 오후 2시부터 4시까지 본사 5층 대강당
- 전 직원 필수 참석

## 3. 8월 급여 지급
- 8월 25일

문의: 인사팀 내선 1234
```

`experiments/corpus-schedule/documents/02-no-schedule.md`:

```markdown
# 사내 편의시설 안내

## 카페테리아
본사 2층에 있으며 음료와 간단한 식사를 제공합니다.

## 회의실 예약
3층 안내데스크에서 접수합니다. 예약은 부서장 승인이 필요합니다.

## 문의
총무팀 내선 2200, 담당자 김철수
```

`experiments/corpus-schedule/expected.json`:

```json
{
  "01-august-notice": {
    "status": "extracted",
    "schedules": [
      {"title": "하계 워크샵", "startAt": "2026-08-12T00:00:00Z",
       "endAt": "2026-08-13T09:00:00Z"},
      {"title": "참가 신청 마감", "startAt": "2026-08-04T15:00:00Z",
       "endAt": "2026-08-05T14:59:59.999999Z"},
      {"title": "정기 안전교육", "startAt": "2026-08-20T05:00:00Z",
       "endAt": "2026-08-20T07:00:00Z"},
      {"title": "8월 급여 지급", "startAt": "2026-08-24T15:00:00Z",
       "endAt": "2026-08-25T14:59:59.999999Z"}
    ]
  },
  "02-no-schedule": {
    "status": "no_schedule",
    "schedules": []
  }
}
```

- [ ] **Step 6: 실호출 테스트를 더한다**

`tests/schedule/test_local_model.py` 끝에 붙인다:

```python
# ---- 실호출 (@pytest.mark.llm) ---------------------------------------------
#
# 기본 실행에서 빠진다. 돌리려면:
#   uv run pytest -m llm -v
# ollama 가 없거나 모델이 없으면 skip 한다.

from datetime import datetime, timezone
from pathlib import Path

CORPUS = Path(__file__).resolve().parents[2] / "experiments" / "corpus-schedule"
LLM_MODEL = "qwen2.5:7b-instruct"
NOW = datetime(2026, 7, 29, 3, 0, tzinfo=timezone.utc)


def _live_provider():
    return OllamaProvider(model=LLM_MODEL, base_url="http://localhost:11434",
                          timeout_seconds=170.0)


def _require_ollama():
    try:
        response = httpx.get("http://localhost:11434/api/tags", timeout=2.0)
        response.raise_for_status()
    except httpx.HTTPError:
        pytest.skip("ollama 가 없다")
    names = [model["name"] for model in response.json().get("models", [])]
    if LLM_MODEL not in names:
        pytest.skip(f"{LLM_MODEL} 가 없다 — ollama pull {LLM_MODEL}")


@pytest.mark.llm
async def test_extracts_every_schedule_from_the_notice():
    """작은 모델은 여기서 0건을 낸다 (설계 §2.1). 7B 급이 하한이다."""
    _require_ollama()
    from schedule_extractor import extract_schedules

    markdown = (CORPUS / "documents" / "01-august-notice.md").read_text(encoding="utf-8")
    result = await extract_schedules(markdown, now=NOW, provider=_live_provider())

    titles = " ".join(item.title for item in result.schedules)
    assert "워크샵" in titles
    assert "안전교육" in titles
    assert "급여" in titles

    workshop = next(item for item in result.schedules if "워크샵" in item.title)
    assert workshop.start_at == datetime(2026, 8, 12, 0, 0, tzinfo=timezone.utc)


@pytest.mark.llm
async def test_document_without_dates_yields_nothing():
    _require_ollama()
    from schedule_extractor import extract_schedules

    markdown = (CORPUS / "documents" / "02-no-schedule.md").read_text(encoding="utf-8")
    result = await extract_schedules(markdown, now=NOW, provider=_live_provider())
    assert result.schedules == ()
```

- [ ] **Step 7: 테스트를 돌린다**

Run: `cd ai && uv run pytest tests/schedule/test_local_model.py -m "not llm" -v`
Expected: 전송 계층 4개 PASS, 실호출 2개 deselect

Run: `cd ai && uv run pytest -m llm -v`
Expected: 2개 PASS (ollama·모델 있을 때). `test_extracts_every_schedule_from_the_notice` 가 25~60초 걸린다

Run: `cd ai && uv run pytest -m "not ocr and not llm" -q`
Expected: 전부 PASS. 실호출이 기본 실행에 섞이지 않는다

- [ ] **Step 8: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106-79-schedule
git status --short
git add ai/src/schedule_extractor/providers ai/tests/schedule/test_local_model.py \
        ai/experiments/corpus-schedule ai/pyproject.toml
git commit -m "feat(ai): 로컬 모델 어댑터와 일정 코퍼스를 더한다

전송 계층은 MockTransport 로 모델 없이 검사하고 실호출은 llm 마커로 분리한다.
출력이 비결정적이고 CI 에 모델이 없어 기본 실행에 섞으면 CI 가 깨진다.

Refs: S15P11B106-79"
```

---

### Task 7: 배포 어댑터·설정·문서 갱신

**Files:**
- Create: `src/schedule_extractor/providers/anthropic.py`
- Create: `src/schedule_extractor/config.py`
- Create: `tests/schedule/test_config.py`
- Create: `tests/schedule/test_anthropic_provider.py`
- Modify: `src/wiki_api/app.py`
- Modify: `src/.env.example`
- Modify: `ai/CLAUDE.md`
- Modify: `ai/README.md`
- Modify: `pyproject.toml` (선택 의존성)

**Interfaces:**
- Consumes: `JsonCompletionProvider` (Task 1), `OllamaProvider` (Task 6)
- Produces: `ScheduleExtractorSettings`, `load_settings() -> ScheduleExtractorSettings`, `build_provider(settings) -> JsonCompletionProvider`, `AnthropicProvider(...)`

**배포 모델은 이 Task 가 정하지 않는다.** GMS 게이트웨이 키가 사용 불가고 배포 서버 GPU 도 미정이다 (설계 §4·§7.2). 어댑터를 만들어 두고 선택은 설정으로 남긴다.

`anthropic` SDK 를 새 의존성으로 넣지 않는다 — `httpx` 로 Messages API 를 직접 부른다. 구조화 출력은 tool use 로 강제한다. 의존성 하나를 아끼는 것이 목적이 아니라, 어댑터가 30줄이면 SDK 버전에 묶이지 않는 쪽이 싸다.

- [ ] **Step 1: 설정 테스트를 쓴다**

`tests/schedule/test_config.py`:

```python
"""어댑터 선택 설정. wiki_mcp/config.py 와 분리한다 — 역의존을 만들지 않는다."""

import pytest

from schedule_extractor.config import build_provider, load_settings
from schedule_extractor.providers.ollama import OllamaProvider


def test_defaults_to_ollama(monkeypatch):
    monkeypatch.delenv("SCHEDULE_EXTRACTOR_PROVIDER", raising=False)
    settings = load_settings(env={})
    assert settings.provider == "ollama"
    assert settings.model == "qwen2.5:7b-instruct"
    assert settings.base_url == "http://localhost:11434"
    assert settings.timeout_seconds == 170.0


def test_timeout_stays_under_the_backend_read_limit():
    """백엔드가 180초에 끊는다. 우리가 먼저 끊어야 계약 오류로 나간다 (설계 §3.6)."""
    assert load_settings(env={}).timeout_seconds < 180.0


def test_env_overrides_every_field():
    settings = load_settings(env={
        "SCHEDULE_EXTRACTOR_PROVIDER": "anthropic",
        "SCHEDULE_EXTRACTOR_MODEL": "claude-opus-4-6",
        "SCHEDULE_EXTRACTOR_BASE_URL": "https://example.invalid",
        "SCHEDULE_EXTRACTOR_TIMEOUT_SECONDS": "90",
        "ANTHROPIC_API_KEY": "test-key",
    })
    assert settings.provider == "anthropic"
    assert settings.model == "claude-opus-4-6"
    assert settings.base_url == "https://example.invalid"
    assert settings.timeout_seconds == 90.0


def test_build_provider_returns_ollama_by_default():
    assert isinstance(build_provider(load_settings(env={})), OllamaProvider)


def test_anthropic_without_a_key_fails_at_startup():
    """기동 때 알아야 한다 — 첫 요청에서 500 을 내는 것보다 낫다."""
    settings = load_settings(env={"SCHEDULE_EXTRACTOR_PROVIDER": "anthropic"})
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        build_provider(settings)


def test_unknown_provider_name_fails():
    settings = load_settings(env={"SCHEDULE_EXTRACTOR_PROVIDER": "gpt"})
    with pytest.raises(ValueError, match="gpt"):
        build_provider(settings)
```

- [ ] **Step 2: 배포 어댑터 테스트를 쓴다**

`tests/schedule/test_anthropic_provider.py`:

```python
"""배포 어댑터. 실제 API 를 부르지 않는다 — MockTransport 로 계약만 확인한다."""

import httpx
import pytest

from schedule_extractor import ProviderError
from schedule_extractor.providers.anthropic import AnthropicProvider

SCHEMA = {"type": "object", "properties": {"schedules": {"type": "array"}},
          "required": ["schedules"]}


def _provider(handler) -> AnthropicProvider:
    return AnthropicProvider(model="claude-opus-4-6", api_key="test-key",
                             base_url="https://api.anthropic.com",
                             timeout_seconds=170.0,
                             transport=httpx.MockTransport(handler))


async def test_forces_structured_output_with_a_tool():
    def handler(request: httpx.Request) -> httpx.Response:
        import json
        body = json.loads(request.content)
        assert request.headers["x-api-key"] == "test-key"
        assert request.headers["anthropic-version"]
        assert body["tool_choice"] == {"type": "tool", "name": "emit_schedules"}
        assert body["tools"][0]["input_schema"] == SCHEMA
        return httpx.Response(200, json={
            "content": [{"type": "tool_use", "name": "emit_schedules",
                         "input": {"schedules": []}}]})

    assert await _provider(handler).complete_json("프롬프트", SCHEMA) == {"schedules": []}


async def test_missing_tool_use_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "content": [{"type": "text", "text": "일정을 찾지 못했습니다"}]})

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)


async def test_http_error_becomes_provider_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": {"message": "rate limited"}})

    with pytest.raises(ProviderError):
        await _provider(handler).complete_json("프롬프트", SCHEMA)
```

- [ ] **Step 3: 실패를 확인한다**

Run: `cd ai && uv run pytest tests/schedule/test_config.py tests/schedule/test_anthropic_provider.py -v`
Expected: `ModuleNotFoundError`

- [ ] **Step 4: 배포 어댑터를 만든다**

`src/schedule_extractor/providers/anthropic.py`:

```python
"""배포 어댑터 — Anthropic Messages API 를 `httpx` 로 직접 부른다.

SDK 를 새 의존성으로 넣지 않는다. 어댑터가 짧아 SDK 버전에 묶이지 않는 쪽이 싸고,
`httpx` 는 이미 의존성에 있다.

**구조화 출력은 tool use 로 강제한다.** 텍스트로 JSON 을 달라고 하면 앞뒤에 설명이
붙어 파싱이 흔들린다. `tool_choice` 로 그 툴을 반드시 부르게 하면 `input` 이 스키마를
지킨 dict 로 온다.

모델 이름은 정확한 이름을 쓴다 — `opus` 별칭은 시점에 따라 다른 모델로 해석돼 두
측정의 비교를 조용히 깨뜨린다 (`ai/CLAUDE.md` 함정).
"""

from __future__ import annotations

import httpx

from ..provider import ProviderError

_TOOL_NAME = "emit_schedules"
_API_VERSION = "2023-06-01"
_MAX_TOKENS = 8192


class AnthropicProvider:
    def __init__(self, *, model: str, api_key: str, base_url: str,
                 timeout_seconds: float,
                 transport: httpx.BaseTransport | None = None):
        self._model = model
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport

    async def complete_json(self, prompt: str, schema: dict) -> dict:
        payload = {
            "model": self._model,
            "max_tokens": _MAX_TOKENS,
            "messages": [{"role": "user", "content": prompt}],
            "tools": [{"name": _TOOL_NAME,
                       "description": "추출한 일정을 구조화해 돌려준다.",
                       "input_schema": schema}],
            "tool_choice": {"type": "tool", "name": _TOOL_NAME},
        }
        headers = {"x-api-key": self._api_key, "anthropic-version": _API_VERSION,
                   "content-type": "application/json"}
        try:
            async with httpx.AsyncClient(timeout=self._timeout,
                                         transport=self._transport) as client:
                response = await client.post(f"{self._base_url}/v1/messages",
                                             json=payload, headers=headers)
                response.raise_for_status()
                blocks = response.json()["content"]
        except (httpx.HTTPError, KeyError, ValueError) as error:
            raise ProviderError(f"Anthropic 호출 실패: {error}") from error

        for block in blocks:
            if block.get("type") == "tool_use" and block.get("name") == _TOOL_NAME:
                return block["input"]
        raise ProviderError("모델이 구조화 출력을 내지 않았다")
```

- [ ] **Step 5: 설정을 만든다**

`src/schedule_extractor/config.py`:

```python
"""어댑터 선택 설정.

`wiki_mcp/config.py` 에 두지 않는다 — 그 파일은 위키 저장 계층 설정(`WORKSPACE_PATH`·
`APP_URL`)이고, 이 패키지는 `wiki_mcp` 를 임포트하지 않는다 (설계 §4).

타임아웃 기본값이 170초인 이유: 백엔드가 180초에 끊는다
(`application.yml:37` `schedule-extraction-read-timeout`). 우리가 먼저 끊어야 계약이
정한 500 으로 나가고, 늦으면 코드도 없는 전송 오류가 되어 실패 사유가 사라진다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from .provider import JsonCompletionProvider

DEFAULT_OLLAMA_MODEL = "qwen2.5:7b-instruct"
DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"
DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
DEFAULT_TIMEOUT_SECONDS = 170.0


@dataclass(frozen=True)
class ScheduleExtractorSettings:
    provider: str
    model: str
    base_url: str
    timeout_seconds: float
    api_key: str


def load_settings(env: dict | None = None) -> ScheduleExtractorSettings:
    source = os.environ if env is None else env
    provider = source.get("SCHEDULE_EXTRACTOR_PROVIDER", "ollama").strip() or "ollama"
    anthropic = provider == "anthropic"
    return ScheduleExtractorSettings(
        provider=provider,
        model=source.get("SCHEDULE_EXTRACTOR_MODEL")
        or ("claude-opus-4-6" if anthropic else DEFAULT_OLLAMA_MODEL),
        base_url=source.get("SCHEDULE_EXTRACTOR_BASE_URL")
        or (DEFAULT_ANTHROPIC_BASE_URL if anthropic else DEFAULT_OLLAMA_BASE_URL),
        timeout_seconds=float(source.get("SCHEDULE_EXTRACTOR_TIMEOUT_SECONDS")
                              or DEFAULT_TIMEOUT_SECONDS),
        api_key=source.get("ANTHROPIC_API_KEY", ""),
    )


def build_provider(settings: ScheduleExtractorSettings) -> JsonCompletionProvider:
    """기동 시점에 어댑터를 만든다 — 설정 오류를 첫 요청이 아니라 기동에서 안다."""
    if settings.provider == "ollama":
        from .providers.ollama import OllamaProvider
        return OllamaProvider(model=settings.model, base_url=settings.base_url,
                              timeout_seconds=settings.timeout_seconds)
    if settings.provider == "anthropic":
        if not settings.api_key:
            raise ValueError(
                "SCHEDULE_EXTRACTOR_PROVIDER=anthropic 인데 ANTHROPIC_API_KEY 가 없다")
        from .providers.anthropic import AnthropicProvider
        return AnthropicProvider(model=settings.model, api_key=settings.api_key,
                                 base_url=settings.base_url,
                                 timeout_seconds=settings.timeout_seconds)
    raise ValueError(f"알 수 없는 SCHEDULE_EXTRACTOR_PROVIDER: {settings.provider}")
```

- [ ] **Step 6: 앱 배선을 마무리한다**

`src/wiki_api/app.py` 의 `app.state.schedule_provider = None` 을 바꾼다:

```python
    from schedule_extractor.config import build_provider, load_settings

    # 기동 시점에 만든다 — 설정 오류를 첫 요청이 아니라 기동에서 안다.
    # 테스트는 create_app() 뒤에 이 속성을 가짜로 바꾼다 (app.state.runtime 과 같은 방식).
    app.state.schedule_provider = build_provider(load_settings())
```

- [ ] **Step 7: 환경변수 템플릿을 더한다**

`src/.env.example` 끝에 붙인다:

```bash
# 일정 추출 LLM 어댑터. ollama(로컬·무과금) 또는 anthropic(배포).
# 배포 모델은 아직 정하지 않았다 — GMS 게이트웨이 키가 사용 불가고 배포 서버 GPU 도 미정이다.
SCHEDULE_EXTRACTOR_PROVIDER=ollama
SCHEDULE_EXTRACTOR_MODEL=qwen2.5:7b-instruct
SCHEDULE_EXTRACTOR_BASE_URL=http://localhost:11434

# 백엔드가 180초에 끊는다. 그보다 먼저 끊어야 계약이 정한 500 으로 나간다.
SCHEDULE_EXTRACTOR_TIMEOUT_SECONDS=170
```

- [ ] **Step 8: 문서를 갱신한다**

`ai/CLAUDE.md` 의 패키지 표에 한 줄 더한다:

```
| `schedule_extractor` | 일정 문서 Markdown → 일정 초안. 상태 없는 단발 LLM 호출. 시각 변환·연도 추론은 코드가 한다 |
```

같은 파일의 의존 그래프를 고친다:

```
Spring Boot --HTTP--> wiki_api --> agent_runtime --> (MCP) --> wiki_mcp
                        ├-> document_parser
                        └-> schedule_extractor
```

"패키지마다 먼저 읽을 파일" 표에 한 줄 더한다:

```
| `schedule_extractor/normalize.py` | 시각 변환·연도 추론·탈락 판정. **모델에게 시간 산술을 시키지 않는 이유가 여기 있다** |
```

의존 방향 항목에 한 문장 더한다: "`schedule_extractor` 는 `wiki_mcp`·`agent_runtime` 을 임포트하지 않는다 — 상태가 없어 저장 계층이 필요 없다."

`ai/README.md` 의 내부 API 표에 한 줄 더한다:

```
| `POST /internal/v1/schedule-extractions` | 일정 문서 Markdown 에서 일정 초안을 뽑는다 |
```

- [ ] **Step 9: 테스트를 돌린다**

Run: `cd ai && uv run pytest tests/schedule -m "not llm" -v`
Expected: 전부 PASS

Run: `cd ai && uv run pytest -m "not ocr and not llm" -q`
Expected: 전부 PASS

`build_provider` 가 기동에서 도니 `create_app()` 이 ollama 없이도 성공해야 한다 — `OllamaProvider` 는 생성 시점에 접속하지 않는다. FAIL 하면 어댑터가 생성자에서 네트워크를 만지는 것이다.

- [ ] **Step 10: 실기동을 확인한다**

```bash
cd ai
INTERNAL_API_KEY=dev-key uv run python -m wiki_api.serve --port 8000 &
sleep 3
curl -s -X POST http://localhost:8000/internal/v1/schedule-extractions \
  -H 'X-Internal-Api-Key: dev-key' -H 'Content-Type: application/json' \
  -d '{"sourceGroupKey":"sg-1","parsedMarkdown":"# 8월 일정\n- 8월 12일 09:00 워크샵\n","visibilityType":"all","departmentIds":[]}' | python3 -m json.tool
kill %1
```

Expected: `status`·`schedules`·`warnings` 가 있는 200. ollama 와 모델이 있어야 한다. 없으면 500 `SCHEDULE_EXTRACTION_FAILED` 가 나오는데 그것도 정상 동작이다 — 내부 상세가 응답에 없는지 확인한다.

- [ ] **Step 11: 커밋**

```bash
cd /home/wolyong/workspace/project/AJT/S15P11B106-79-schedule
git status --short
git add ai/src/schedule_extractor/config.py ai/src/schedule_extractor/providers/anthropic.py \
        ai/src/wiki_api/app.py ai/src/.env.example ai/CLAUDE.md ai/README.md \
        ai/tests/schedule/test_config.py ai/tests/schedule/test_anthropic_provider.py
git commit -m "feat(ai): 일정 추출 배포 어댑터와 설정을 더한다

어댑터를 기동 시점에 만들어 설정 오류를 첫 요청이 아니라 기동에서 잡는다.
타임아웃 기본값은 170초다 — 백엔드가 180초에 끊기 전에 우리가 끊어야 계약 오류로 나간다.

Refs: S15P11B106-79"
```

---

## 완료 판정

- [ ] `cd ai && uv run pytest -m "not ocr and not llm"` 전부 통과
- [ ] `cd ai && uv run pytest -m llm` 통과 (ollama + `qwen2.5:7b-instruct` 있을 때)
- [ ] 계약 예시 요청이 200 과 계약 응답 형태를 돌려준다
- [ ] `parsedMarkdown` 빈 값·미지원 `visibilityType`·정의 없는 필드가 400 `INVALID_SCHEDULE_EXTRACTION_REQUEST`
- [ ] 키 없음·불일치가 401
- [ ] provider 실패가 500 `SCHEDULE_EXTRACTION_FAILED` 이고 **내부 상세가 응답에 없다**
- [ ] 날짜 없는 문서 + 모델 0건이 200 `no_schedule`
- [ ] 날짜 있는 문서 + 모델 0건이 500
- [ ] 종일 일정 종료가 `...T14:59:59.999999Z` 로 나간다
- [ ] `ai/CLAUDE.md`·`ai/README.md`·`src/.env.example` 갱신
- [ ] `git status` 에 `ai/` 밖 변경이 없다

## MR 본문에 적을 협의 항목

설계 §7.1 에서 온다. 계약·요구사항 변경이 필요하거나 백엔드에 전달할 것들이다.

| 항목 | 상대 |
| --- | --- |
| 요청에 `referenceDate`·`timezone` 추가 — 지금은 `Asia/Seoul` + 서버 시각 가정이고 결과가 시점에 따라 달라진다 | 계약 |
| `warnings` 를 관리자에게 노출할 경로 — 백엔드가 버리고 공개 API 에 필드가 없다 | 계약·요구사항·프론트 |
| 파싱 `read-timeout` — `FR-AI-001` 은 파싱+추출 180초인데 파싱이 공용 10분을 쓴다 | 백엔드 `S15P11B106-121` |
| `parsedMarkdown` 바이트 상한 — 계약에 없고 파일 20MB 에서 유도했다 | 계약 |
| 일정 추출 `failureStage` 어휘와 계약 필드 — `NFR-AI-003` 첫 문장이 요구한다 | 계약·요구사항 |
| 백엔드 응답 검증 강화 — `status` 허용값·목록 일관성·`order` 중복을 안 본다 | 백엔드 |
| CSV·XLSX 파싱 — 계약이 허용하는데 파서가 없고 **어느 티켓도 맡고 있지 않다** | 팀 (새 티켓) |

## 범위 밖

| 항목 | 이유 |
| --- | --- |
| CSV·XLSX 파서 확장 | 별 티켓. `S15P11B106-118` 이 명시로 제외했다 |
| 챗봇 답변 API 2개 | 계약에 있고 AI 에 없다. 별 안건 |
| 배포 모델 확정 | 로컬 모델 품질 측정과 배포 서버 GPU 결정 뒤 |
| 큰 문서 2단 파이프라인 | 누락이 관측되면. 설계 §6.1 |
| `exaone3.5` 대조 측정 | 코퍼스가 문서 2건이라 아직 대조의 의미가 없다 |
