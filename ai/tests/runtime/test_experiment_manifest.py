"""`manifest.json` 이 측정 조건을 빠짐없이 적는가 — 계획
`2026-07-29-wiki-context-hardening.md` Task 5 Step 3.

`experiments/INDEX.md` 가 `manifest.json` 을 두는 이유를 적어 뒀다: 이 세션에서 두 번 손해를
봤고, 12건 런 하나가 프롬프트 수정 **전** 코드로 돌았다는 것을 파일 mtime 비교로 뒤늦게
알아냈다. **리포트가 자기 출처를 들고 있어야 한다.**

그런데 세 조건이 빠져 있었다.

  * `effort` — 지금까지 모든 측정이 CLI 기본값으로 돌았고 그 값이 기록되지 않았다 (D8).
    `--effort` 를 지정할 수 있게 된 뒤에는 어느 단계로 쟀는지 모르면 대조가 무의미하다.
  * `cliVersion` — 스폰된 CLI 판본에 따라 툴 표면이 달라진다. 2026-07-27 측정은 MCP 툴을
    정상적으로 불렀는데 2026-07-30 에는 지연 로딩으로 못 불렀다 (`INDEX.md` 「측정을 막고
    있는 것」). 판본을 안 적으면 그 차이를 나중에 설명할 수 없다.
  * `settingSources` — 스폰된 CLI 가 운영자 `~/.claude` 를 물려받는다. 측정 결과가 측정한
    사람의 설정에 의존한다.

이 파일은 그 세 개가 `manifest.json` 에 남는 것을 지킨다. **측정은 되돌릴 수 없으므로
조건 기록이 빠진 것을 사후에 메울 방법이 없다.**
"""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "experiments"))

from experiment import Experiment  # noqa: E402


@pytest.fixture
def experiment(tmp_path, monkeypatch):
    """`EXPERIMENTS` 를 임시 디렉터리로 돌린다 — 실제 `experiments/` 아래에 테스트
    디렉터리를 만들면 `experiments.py` 표에 섞인다."""
    import experiment as module

    monkeypatch.setattr(module, "EXPERIMENTS", tmp_path)
    return Experiment("2026-07-30-test")


def _start(experiment, **overrides) -> dict:
    kwargs = {
        "runtime": "claude-code",
        "model": "claude-opus-4-6",
        "scope": "ALL",
        "corpus": ["01-training.md"],
        "purpose": "테스트",
    }
    kwargs.update(overrides)
    return experiment.start(**kwargs)


def test_the_manifest_records_the_effort_level(experiment):
    """지금까지 모든 측정이 CLI 기본값으로 돌았고 그 값이 남지 않았다 (D8). `--effort`
    대조 측정은 어느 단계로 쟀는지 적히지 않으면 해석할 수 없다."""
    manifest = _start(experiment, effort="low")
    assert manifest["effort"] == "low"


def test_an_unset_effort_is_recorded_as_such(experiment):
    """지정하지 않은 것과 `low` 로 지정한 것은 다르다. `None` 이 "CLI 기본값" 을 뜻하고,
    키가 아예 없으면 "기록을 안 했다" 와 구별되지 않는다."""
    manifest = _start(experiment)
    assert "effort" in manifest
    assert manifest["effort"] is None


def test_the_manifest_records_the_cli_version(experiment):
    """스폰된 CLI 판본에 따라 툴 표면이 달라진다. 2026-07-27 측정은 MCP 툴을 정상적으로
    불렀고 2026-07-30 에는 지연 로딩으로 못 불렀다 — 판본을 안 적으면 나중에 설명할 수 없다."""
    manifest = _start(experiment)
    assert "cliVersion" in manifest


def test_the_manifest_records_the_setting_sources(experiment):
    """스폰된 CLI 가 운영자 `~/.claude` 를 물려받는다. 그것을 격리했는지가 결과를 바꾼다."""
    manifest = _start(experiment)
    assert "settingSources" in manifest


def test_the_manifest_is_written_to_disk(experiment):
    _start(experiment, effort="high")
    saved = json.loads(experiment.manifest_path.read_text(encoding="utf-8"))
    assert saved["effort"] == "high"
    assert "cliVersion" in saved


def test_the_existing_fields_are_untouched(experiment):
    """앞선 측정들과 대조하려면 기존 키가 그대로여야 한다."""
    manifest = _start(experiment)
    for key in ("slug", "startedAt", "runtime", "model", "scope", "corpus",
                "purpose", "dryRun", "code"):
        assert key in manifest, key


def test_a_finished_measurement_is_not_overwritten(experiment):
    """한 시간짜리 측정은 되돌릴 수 없다. 이 성질이 깨지면 안 된다."""
    _start(experiment)
    experiment.finish({"documents": []})
    with pytest.raises(FileExistsError):
        _start(experiment)


# ----- effort 가 기록만 되고 실행에 안 걸리면 그 기록은 거짓이다 ---------------

def test_load_runtime_passes_effort_to_the_cli_runtime():
    """`manifest.json` 에 `effort: "low"` 라고 적히는데 실행이 기본값으로 돌면 그 기록이
    거짓이다. 대조 측정 전체가 무의미해진다."""
    from agent_runtime import load_runtime

    runtime = load_runtime("claude-code", effort="low")
    assert runtime.effort == "low"


def test_load_runtime_leaves_effort_unset_by_default():
    from agent_runtime import load_runtime

    assert load_runtime("claude-code").effort is None


def test_an_unknown_effort_is_rejected_before_the_run_starts():
    """한 시간짜리 측정을 시작한 뒤 오타를 알면 그 시간이 사라진다."""
    from agent_runtime import load_runtime

    with pytest.raises(ValueError):
        load_runtime("claude-code", effort="medum")


# ----- via-api 경로에서는 effort 를 받지 않는다 -------------------------------

def test_effort_is_rejected_with_via_api(monkeypatch, capsys):
    """`--via-api` 는 AI 서버가 자기 런타임으로 돈다. 여기서 준 `--effort` 는 어디에도 안
    걸리는데 `manifest.json` 에는 적힌다 — 거짓 기록이다."""
    import backend_sim

    monkeypatch.setattr(sys, "argv", [
        "backend_sim", "a.md", "--effort", "low",
        "--via-api", "http://127.0.0.1:8000"])
    with pytest.raises(SystemExit):
        backend_sim.main()
    assert "--effort" in capsys.readouterr().err


# ----- report.json 에 턴별 내역이 담긴다 --------------------------------------

def test_the_record_carries_the_stream_detail():
    """총계만으로는 D8 을 좁힐 수 없다. `RunResult.detail` 이 `report.json` 까지 가야 한다."""
    import inspect

    import backend_sim

    source = inspect.getsource(backend_sim._ingest_one)
    assert 'record["detail"] = result.detail' in source


def test_a_tool_call_mismatch_is_recorded():
    """서버측 집계와 스트림측 집계가 다르면 CLI 가 부른 툴이 서버에 도달하지 않았다는
    뜻이다. 실제로 그 상황을 겪었다 — 스트림에 `Bash` 10회, 서버측 0회
    (`INDEX.md` 「측정을 막고 있는 것」). 어느 한쪽만으로는 알 수 없다."""
    import inspect

    import backend_sim

    assert "toolCallMismatch" in inspect.getsource(backend_sim._ingest_one)
