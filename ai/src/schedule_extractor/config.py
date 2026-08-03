"""어댑터 선택 — 설정 **모양**과 어댑터 조립만 안다. 환경은 읽지 않는다.

**`os.environ` 을 보지 않는다.** 설정은 가장자리(`wiki_api/settings.py` 의
`ServerSettings`)에서 한 번 읽고 인자로 내려온다 — 안쪽 코드가 환경을 읽으면 설정 출처가
갈리고 어디에 넣어야 먹는지 아무도 모르게 된다 (`wiki_api/settings.py` 참고).

`ScheduleExtractorSettings` 는 평범한 dataclass 다. `credential_table` 이 평범한 `dict` 를
돌려주는 것과 같은 이유로, 이 패키지가 `ServerSettings` 를 몰라도 되게 한다.

배포 모델 기본값이 `claude-haiku-4-5-20251001` 인 이유: 일정 추출은 상태 없는 단발
구조화 출력이라 값싼 티어로 충분하고(haiku 로 데모 코퍼스 4/4 정확 추출 확인, 2026-08-02),
GMS 게이트웨이가 프록시하는 목록에도 있다. 더 큰 모델이 필요하면 값 자체는 설정으로 남긴다.
"""

from __future__ import annotations

from dataclasses import dataclass

from .provider import JsonCompletionProvider

DEFAULT_OLLAMA_MODEL = "qwen2.5:7b-instruct"
DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"
DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001"
DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
DEFAULT_TIMEOUT_SECONDS = 170.0
"""백엔드가 180초에 끊는다(`application.yml` `schedule-extraction-read-timeout`).
우리가 먼저 끊어야 계약이 정한 500 으로 나가고, 늦으면 코드도 없는 전송 오류가 되어
실패 사유가 사라진다."""

PROVIDERS = ("ollama", "anthropic")


@dataclass(frozen=True)
class ScheduleExtractorSettings:
    provider: str = "ollama"
    model: str = ""
    base_url: str = ""
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    api_key: str = ""

    def resolved(self) -> "ScheduleExtractorSettings":
        """빈 `model`·`base_url` 을 프로바이더별 기본값으로 채운다.

        기본값을 여기서 채우는 이유: 프로바이더를 아는 곳이 여기다. `ServerSettings` 가
        채우면 `anthropic` 기본 모델을 위쪽 층이 알아야 한다.
        """
        anthropic = self.provider == "anthropic"
        return ScheduleExtractorSettings(
            provider=self.provider,
            model=self.model
            or (DEFAULT_ANTHROPIC_MODEL if anthropic else DEFAULT_OLLAMA_MODEL),
            base_url=self.base_url
            or (DEFAULT_ANTHROPIC_BASE_URL if anthropic else DEFAULT_OLLAMA_BASE_URL),
            timeout_seconds=self.timeout_seconds,
            api_key=self.api_key)


def build_provider(settings: ScheduleExtractorSettings) -> JsonCompletionProvider:
    """기동 시점에 어댑터를 만든다 — 설정 오류를 첫 요청이 아니라 기동에서 안다."""
    resolved = settings.resolved()
    if resolved.provider == "ollama":
        from .providers.ollama import OllamaProvider
        return OllamaProvider(model=resolved.model, base_url=resolved.base_url,
                              timeout_seconds=resolved.timeout_seconds)
    if resolved.provider == "anthropic":
        if not resolved.api_key:
            raise ValueError(
                "SCHEDULE_EXTRACTOR_PROVIDER=anthropic 인데 ANTHROPIC_API_KEY 가 없다")
        from .providers.anthropic import AnthropicProvider
        return AnthropicProvider(model=resolved.model, api_key=resolved.api_key,
                                 base_url=resolved.base_url,
                                 timeout_seconds=resolved.timeout_seconds)
    raise ValueError(f"알 수 없는 SCHEDULE_EXTRACTOR_PROVIDER: {resolved.provider}")
