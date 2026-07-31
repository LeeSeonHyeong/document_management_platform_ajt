def test_scope_changed_failure_stage_exists():
    """계약 v1.5.0·요구사항 v2.11 이 정의한 값이다.

    변환 중 같은 scope_key 의 Wiki 가 바뀌어(DR-030) 반영하지 않고 종료한 경우.
    """
    from wiki_api.errors import FailureStage

    assert FailureStage.SCOPE_CHANGED.value == "scope_changed"


def test_failure_stages_cover_requirement_list():
    """NFR-AI-003 이 열거한 여섯 단계가 모두 있는지 확인한다.

    agent_start 는 요구사항에 없는 코드 쪽 추가값이다 — 이 테스트는 요구사항이
    요구한 것이 빠지지 않았는지만 본다.
    """
    from wiki_api.errors import FailureStage

    required = {
        "context_load", "agent_timeout", "agent_error",
        "lint_failed", "assemble", "scope_changed",
    }
    assert required <= {stage.value for stage in FailureStage}
