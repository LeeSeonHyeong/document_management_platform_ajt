"""서버 설정 한 곳.

**설정은 가장자리에서 한 번 읽고 인자로 내린다.** 안쪽 코드가 `os.environ` 을 보지
않는다 — 그렇게 하면 설정 출처가 갈리고, 어디에 넣어야 먹는지 아무도 모르게 된다.
실제로 그 상태였다: `.env` 에 API 키를 넣어도 조용히 무시됐다.

우선순위는 `초기화 인자 > 환경변수 > .env > 기본값` 이고 **pydantic-settings 의 기본
동작이 그것이다.** `load_dotenv` 로 `os.environ` 에 올리지 않는다 — 전역 가변 상태를
만들고, 그것이 애초의 문제다.

`.env` 파일은 `wiki_mcp/config.py` 와 같은 `src/.env` 다. 파일 하나를 두 층이 읽고
각 층은 자기 필드만 선언한다. 남의 변수는 `extra="ignore"` 로 통과한다.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from schedule_extractor.config import (DEFAULT_TIMEOUT_SECONDS, PROVIDERS,
                                       ScheduleExtractorSettings)

_ENV_FILE = Path(__file__).resolve().parent.parent / ".env"

RUNTIMES = ("claude-code", "deepagents")
VISION_OCR_PROVIDERS = ("gemini", "anthropic")


class ServerSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=str(_ENV_FILE),
        extra="ignore",
        populate_by_name=True,
    )

    internal_api_key: str = Field("", validation_alias="INTERNAL_API_KEY")
    backend_base_url: str = Field("", validation_alias="BACKEND_BASE_URL")

    # S15P11B106-175 가 요청 본문으로 위키를 받는 push 경로를 지웠다 — 이제 위키
    # 엔드포인트는 도구가 같은 프로세스에서 도는 런타임(`arun`)에서만 성립한다
    # (`wiki_api/session.py._assert_runtime_can_use_the_gateway`). `claude-code` 는
    # 하위 프로세스라 그 조건을 만족하지 못해 위키 엔드포인트를 하나도 못 쓴다.
    # 그래서 기본값을 `deepagents` 로 둔다 — `claude-code` 는 로컬에서 명시로만 쓴다
    # (`--runtime claude-code` 또는 `AI_RUNTIME=claude-code`), 그 경우에도 챗봇
    # (`/answers`)·파싱(`/source-parses`)은 그대로 된다.
    runtime: str = Field("deepagents", validation_alias="AI_RUNTIME")
    # 정확한 이름을 쓴다 (`anthropic:claude-opus-4-6`). 별칭은 시점에 따라 다른 모델로
    # 해석돼 두 측정의 비교를 조용히 깨뜨린다.
    model: str | None = Field(None, validation_alias="AI_MODEL")
    model_fast: str | None = Field(None, validation_alias="AI_MODEL_FAST")
    model_quality: str | None = Field(None, validation_alias="AI_MODEL_QUALITY")

    anthropic_api_key: str = Field("", validation_alias="ANTHROPIC_API_KEY")
    anthropic_base_url: str = Field("", validation_alias="ANTHROPIC_BASE_URL")
    openai_api_key: str = Field("", validation_alias="OPENAI_API_KEY")
    openai_base_url: str = Field("", validation_alias="OPENAI_BASE_URL")

    # LangSmith 는 이 앱의 설정 객체가 아니라 `os.environ` 을 직접 보는 서드파티 SDK다
    # (`serve.py::configure_langsmith` 가 유일하게 이 필드들을 예외적으로 `os.environ`에
    # 되심는다). 여기서 선언하지 않으면 `extra="ignore"` 때문에 `.env` 값이 조용히
    # 버려진다 — 실제로 그랬다(2026-08-04, 트레이스가 하나도 안 남았는데 원인을 몰랐다).
    langsmith_tracing: bool = Field(False, validation_alias="LANGSMITH_TRACING")
    langsmith_api_key: str = Field("", validation_alias="LANGSMITH_API_KEY")
    langsmith_project: str = Field("", validation_alias="LANGSMITH_PROJECT")

    # 스캔 PDF 비전 OCR 이 쓸 프로바이더. 기본은 gemini 다 — GMS 게이트웨이의 요청
    # 크기 상한(실측 약 100KB)에 200dpi 로 렌더한 실제 스캔 페이지 대부분이 걸려
    # "model not found" 로 잘못 보고되는 문제가 있었다. Google API 는 GMS 를 거치지
    # 않아 이 제약이 없다. `anthropic` 으로 명시하면 예전처럼 FAST 티어를 재사용한다.
    vision_ocr_provider: str = Field("gemini", validation_alias="VISION_OCR_PROVIDER")
    gemini_api_key: str = Field("", validation_alias="GEMINI_API_KEY")
    # 비우면 어댑터 코드 기본값(무료 티어에 적합하다고 알려진 이름, 실기동 미검증).
    gemini_model: str = Field("", validation_alias="GEMINI_MODEL")
    # 비우면 어댑터가 공개 Google API 주소를 쓴다. 테스트용 프록시가 있을 때만 준다.
    gemini_base_url: str = Field("", validation_alias="GEMINI_BASE_URL")

    # 일정 추출은 에이전트 런타임을 쓰지 않는다 — 상태 없는 단발 구조화 출력이고
    # `completion.complete()` 는 스키마 강제를 하지 않는다. 그래서 자기 어댑터를 고른다.
    # 모델·주소를 비우면 프로바이더별 기본값이 채워진다 (`schedule_extractor/config.py`).
    schedule_provider: str = Field(
        "anthropic", validation_alias="SCHEDULE_EXTRACTOR_PROVIDER")
    schedule_model: str = Field("", validation_alias="SCHEDULE_EXTRACTOR_MODEL")
    schedule_base_url: str = Field("", validation_alias="SCHEDULE_EXTRACTOR_BASE_URL")
    schedule_timeout_seconds: float = Field(
        DEFAULT_TIMEOUT_SECONDS,
        validation_alias="SCHEDULE_EXTRACTOR_TIMEOUT_SECONDS")

    @field_validator("schedule_provider")
    @classmethod
    def _known_schedule_provider(cls, value: str) -> str:
        """`AI_RUNTIME` 과 같은 이유로 오타를 기동 시점에 막는다."""
        if value not in PROVIDERS:
            raise ValueError(
                f"SCHEDULE_EXTRACTOR_PROVIDER 값이 올바르지 않다: {value!r} — "
                f"{', '.join(PROVIDERS)} 중 하나여야 한다")
        return value

    @field_validator("vision_ocr_provider")
    @classmethod
    def _known_vision_ocr_provider(cls, value: str) -> str:
        """`AI_RUNTIME` 과 같은 이유로 오타를 기동 시점에 막는다."""
        if value not in VISION_OCR_PROVIDERS:
            raise ValueError(
                f"VISION_OCR_PROVIDER 값이 올바르지 않다: {value!r} — "
                f"{', '.join(VISION_OCR_PROVIDERS)} 중 하나여야 한다")
        return value

    @field_validator("runtime")
    @classmethod
    def _known_runtime(cls, value: str) -> str:
        """오타를 여기서 막는다.

        `argparse` 의 `choices` 는 기본값을 검사하지 않아 `AI_RUNTIME=deepagent` 가
        조용히 통과했다. 그러면 `deepagents` 로 뜬 줄 알고 배포한 채 첫 변환 요청에서
        실패한다.
        """
        if value not in RUNTIMES:
            raise ValueError(
                f"AI_RUNTIME 값이 올바르지 않다: {value!r} — "
                f"{', '.join(RUNTIMES)} 중 하나여야 한다")
        return value


# 프로바이더 접두사 → 설정 필드 이름. 여기서 프로바이더 목록을 관리하지 않는다 —
# 모르는 접두사는 빈 값을 돌려주고 SDK 의 기본 동작에 맡긴다.
_CREDENTIAL_FIELDS = {
    "anthropic": ("anthropic_api_key", "anthropic_base_url"),
    "openai": ("openai_api_key", "openai_base_url"),
}


def credentials_for(model: str | None, settings: ServerSettings) -> tuple[str, str]:
    """모델 문자열의 `provider:name` 접두사로 `(api_key, base_url)` 을 고른다.

    빈 값을 그대로 돌려주는 것이 의도다. 호출자가 빈 값을 SDK 에 넘기지 않는다 —
    빈 문자열을 넘기면 SDK 가 「빈 키」로 읽어 자기 폴백조차 막는다.

    **`serve.py` 는 이 함수를 더 이상 쓰지 않는다** — 에이전트 모델 하나만 보고 자격증명
    하나를 고르면, 에이전트와 티어 모델의 프로바이더가 갈릴 때 한쪽이 틀린 벤더의 키를
    받는다 (Important 1). `credential_table` 이 그 자리를 대신한다. 이 함수는 "모델
    하나 → 자격증명 하나" 라는 더 단순한 질문에는 여전히 유효해 테스트에 남긴다.
    """
    provider = (model or "").split(":", 1)[0]
    fields = _CREDENTIAL_FIELDS.get(provider)
    if fields is None:
        return "", ""
    return getattr(settings, fields[0]), getattr(settings, fields[1])


def schedule_settings(settings: ServerSettings) -> ScheduleExtractorSettings:
    """일정 추출 어댑터 설정만 떼어낸다.

    `credential_table` 과 같은 이유로 평범한 자료형을 돌려준다 — `schedule_extractor` 가
    `ServerSettings` 를 몰라도 되게 한다.

    `anthropic` 일 때 주소·키를 프로바이더 공용 필드에서 가져온다. GMS 게이트웨이를 쓰면
    `ANTHROPIC_BASE_URL` 하나로 에이전트 런타임과 일정 추출이 같은 경로를 타야 하고,
    키를 두 번 적게 하면 한쪽만 갱신되는 사고가 난다. `SCHEDULE_EXTRACTOR_BASE_URL` 을
    직접 주면 그것이 이긴다 — 일정 추출만 다른 주소로 보내는 측정을 위해서다.
    """
    anthropic = settings.schedule_provider == "anthropic"
    base_url = settings.schedule_base_url
    if not base_url and anthropic:
        base_url = settings.anthropic_base_url
    return ScheduleExtractorSettings(
        provider=settings.schedule_provider,
        model=settings.schedule_model,
        base_url=base_url,
        timeout_seconds=settings.schedule_timeout_seconds,
        api_key=settings.anthropic_api_key if anthropic else "")


def vision_ocr_engine(settings: ServerSettings):
    """스캔 PDF OCR 을 돌리는 엔진을 고른다. 설정이 없으면 `None` → 로컬 Tesseract.

    로컬 Tesseract 는 배포 환경에 언어 데이터(kor)를 깔아야 하고, 안 깔린 곳에선 한글
    스캔 PDF 가 통째로 실패한다 (S15P11B106-180).

    기본 프로바이더는 `gemini` 다 — GMS 게이트웨이는 요청 본문 크기를 실측 약 100KB로
    제한하는데, 200dpi 로 렌더한 실제 스캔 페이지(보통 100~200KB)가 거의 항상 여기
    걸려 `[GMS 에러] Model not found in request` 로 잘못 보고됐다(모델 이름 문제가
    아니라 게이트웨이가 큰 요청을 못 읽어서 생기는 오류). Google Generative Language
    API 는 GMS 를 거치지 않아 이 제약이 없다.

    `anthropic` 으로 명시하면 예전처럼 저렴한 FAST 티어 모델(`AI_MODEL_FAST`, 보통
    haiku)과 GMS 자격을 재사용한다 — 다만 위 크기 제한을 그대로 물려받는다.

    `None` 을 돌려주면 `parse_pdf` 가 로컬 Tesseract 로 폴백한다 — 키·모델이 없으면
    그렇게 둔다(오류로 기동을 막지 않는다).
    """
    if settings.vision_ocr_provider == "gemini":
        if not settings.gemini_api_key:
            return None
        from document_parser.gemini_vision_ocr import GeminiVisionOcr
        kwargs: dict = {"api_key": settings.gemini_api_key,
                        "timeout_seconds": settings.schedule_timeout_seconds}
        if settings.gemini_model:
            kwargs["model"] = settings.gemini_model
        if settings.gemini_base_url:
            kwargs["base_url"] = settings.gemini_base_url
        return GeminiVisionOcr(**kwargs)

    # anthropic: 기존 FAST 티어(GMS/haiku) 경로.
    model = settings.model_fast
    if not model:
        return None
    provider, _, name = model.partition(":")
    if provider != "anthropic" or not name:
        return None
    if not settings.anthropic_api_key or not settings.anthropic_base_url:
        return None
    from document_parser.vision_ocr import AnthropicVisionOcr
    return AnthropicVisionOcr(
        model=name, api_key=settings.anthropic_api_key,
        base_url=settings.anthropic_base_url,
        timeout_seconds=settings.schedule_timeout_seconds)


def credential_table(settings: ServerSettings) -> dict[str, tuple[str, str]]:
    """프로바이더 이름 → `(api_key, base_url)` 표. 값이 하나도 없는 프로바이더는 뺀다.

    `load_runtime` 에 이 표를 그대로 넘긴다. 에이전트 모델과 티어 모델
    (`AI_MODEL_FAST`·`AI_MODEL_QUALITY`)의 프로바이더가 다를 수 있어서 모델 하나 기준
    쌍 하나로는 부족하다 — `DeepAgentsRuntime` 이 호출할 모델마다 이 표에서 자기
    프로바이더 몫을 조회한다.

    평범한 `dict` 를 돌려준다. `agent_runtime` 이 `ServerSettings` 를 몰라도 되게 하기
    위해서다 (의존 방향은 `wiki_api → agent_runtime → wiki_mcp` 단방향).
    """
    table: dict[str, tuple[str, str]] = {}
    for provider, fields in _CREDENTIAL_FIELDS.items():
        api_key, base_url = getattr(settings, fields[0]), getattr(settings, fields[1])
        if api_key or base_url:
            table[provider] = (api_key, base_url)
    return table
