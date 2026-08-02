"""문서 1건당 시간 상한.

NFR-PERF-002는 고정 10분이고 등급이 `권장`이다. 12건 측정에서 3건이 초과했는데 그 3건은
고장이 아니라 문서가 컸다. 상한을 예상 시간의 배수로 바꿔 정상적으로 오래 걸리는 작업을
죽이지 않게 한다. 회귀식은 `experiments/2026-07-27-opus46-12docs` 에서 나왔다.
"""

from agent_runtime.limits import exceeds_ceiling, time_limit_seconds


def test_small_document_gets_at_least_the_floor():
    """작은 문서라도 최소 시간(floor)을 받는다.

    시간 한도는 새 문서 글자수로만 계산되는데, 작은 개정문서가 기존 위키 여러 개와의
    병합·모순해소를 유발하면 그 작은 글자수 예산으로는 부족해 작업 도중 죽는다
    (실측: 679자 문서가 위키 3개 병합 중 201초에 잘림, 17턴/60턴). floor 로 병합-무거운
    작은 문서에 여유를 준다 — 간단한 문서는 그 전에 끝나므로 floor 는 kill 임계만 올린다.
    """
    assert time_limit_seconds(679) == 600


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
