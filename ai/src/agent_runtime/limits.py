"""문서 1건당 시간 상한.

NFR-PERF-002는 "문서 1건 분석당 10분"이며 등급이 `권장`이다. 크기를 보지 않는 규칙이라
12건 측정에서 3건이 초과했고, 그 3건은 고장이 아니라 문서가 컸다. 정상적으로 오래 걸리는
작업을 죽이는 상한은 쓸모가 없다.

회귀식은 `experiments/2026-07-27-opus46-12docs` 12건에서 나왔다 (R² 0.853):

    시간 = 1.9분 + 5.0분 × (글자수 / 1만)

과대 예측하는 방향이라 상한으로 쓰기에 안전하다 — 31,514자 문서는 예상 17.7분인데 실제
9.4분에 끝났다. 상한은 여전히 진짜 멈춘 작업을 잡는다.
"""

from __future__ import annotations

FIXED_MINUTES = 1.9
MINUTES_PER_10K = 5.0
SAFETY_FACTOR = 1.5
DEFAULT_CEILING_SECONDS = 1800


def expected_seconds(char_count: int) -> float:
    """회귀식이 예측하는 소요 시간."""
    return (FIXED_MINUTES + MINUTES_PER_10K * (char_count / 10_000)) * 60


def time_limit_seconds(char_count: int, *,
                       ceiling_seconds: int = DEFAULT_CEILING_SECONDS) -> int:
    """이 문서에 허용할 초. 천장을 넘지 않는다."""
    return min(int(expected_seconds(char_count) * SAFETY_FACTOR), ceiling_seconds)


def exceeds_ceiling(char_count: int, *,
                    ceiling_seconds: int = DEFAULT_CEILING_SECONDS) -> bool:
    """천장 안에 끝날 수 없는 크기인가.

    True면 업로드 단계에서 거부해야 한다 — 30분 기다린 뒤 실패를 알리는 것보다 낫다.
    거부는 Spring이 한다 (FR-DOC-001 파일 검증에 조건 하나가 붙는다).
    """
    return expected_seconds(char_count) * SAFETY_FACTOR > ceiling_seconds
