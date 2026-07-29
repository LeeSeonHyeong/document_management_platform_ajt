"""문서 1건당 시간 상한.

NFR-PERF-002는 고정 10분이고 등급이 `권장`이다. 12건 측정에서 3건이 초과했는데 그 3건은
고장이 아니라 문서가 컸다. 상한을 예상 시간의 배수로 바꿔 정상적으로 오래 걸리는 작업을
죽이지 않게 한다. 회귀식은 `experiments/2026-07-27-opus46-12docs` 에서 나왔다.
"""

from agent_runtime.limits import exceeds_ceiling, time_limit_seconds


def test_small_document_gets_a_short_limit():
    # 2,423자 (01-training.md). 예상 3.11분 × 1.5 = 4.67분 = 280초
    assert time_limit_seconds(2423) == 280


def test_medium_document():
    # 10,016자 (06-security.md). 예상 6.91분 × 1.5 = 10.36분 = 621초
    assert time_limit_seconds(10016) == 621


def test_large_document_gets_more_than_the_old_fixed_limit():
    """26,139자(11-offsites.md)는 실제로 14.9분 걸렸고 고정 10분 규칙에서는 실패했다."""
    limit = time_limit_seconds(26139)
    assert limit == 1347          # 예상 15.0695분 × 1.5 = 22.60분 (정수: 1347초)
    assert limit > 14.9 * 60      # 실제 소요를 넘는다 — 죽이지 않는다


def test_ceiling_caps_the_limit():
    assert time_limit_seconds(10_000_000) == 1800


def test_ceiling_is_configurable():
    assert time_limit_seconds(10_000_000, ceiling_seconds=600) == 600


def test_exceeds_ceiling_flags_documents_too_big_to_finish():
    """천장 30분에 대응하는 크기는 약 36,200자다."""
    assert exceeds_ceiling(36_000) is False
    assert exceeds_ceiling(40_000) is True
