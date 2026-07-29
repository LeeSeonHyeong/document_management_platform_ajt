"""Experiment layout: one measurement, one directory.

    experiments/<slug>/
      manifest.json   when, with what, why — and which code produced it
      report.json     backend_sim output
      notes.md        what it was meant to show, and what it showed
      data/           the storage root (wiki/, work/, .llmwiki/)

`manifest.json` exists because a report could not say which code produced it, and
that cost real time twice: a 12-document run turned out to have used a prompt that
was edited minutes later (found by comparing file mtimes), and a workspace turned
out to predate a schema change (found by failing to open it). There is no git here,
so the run has to carry its own provenance.

Disposable, like `backend_sim.py` — the layout is for measuring, not for shipping.
"""

from __future__ import annotations

import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# 이관: 옛 트리는 ai-server/experiments/ 였다. 지금은 이 파일이 그 안에 있다.
EXPERIMENTS = ROOT
ARCHIVE = EXPERIMENTS / "_archive"

MANIFEST = "manifest.json"
REPORT = "report.json"
NOTES = "notes.md"
DATA = "data"


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def code_fingerprint() -> dict:
    """What the agent was actually given.

    `guideSha` is the load-bearing one: the guide is revised between runs and
    nothing else records which revision a result came from. Imported here rather
    than in `backend_sim` so a caller cannot forget to include it.
    """
    sys.path.insert(0, str(ROOT.parent / "src"))
    from wiki_mcp.tools.guide import GUIDE_TEXT
    from wiki_mcp.tools import register as _register  # noqa: F401  (import check only)

    from agent_runtime.base import ingest_instruction

    schema = (ROOT.parent / "src" / "wiki_mcp" / "shared" / "schema.sql").read_text(encoding="utf-8")
    guide_text = GUIDE_TEXT
    # A fixed sample, so the hash tracks wording rather than the arguments.
    instruction = ingest_instruction("sources/1/parsed/content.md", "ALL")

    return {
        "guideChars": len(guide_text),
        "guideSha": _sha(guide_text),
        "schemaSha": _sha(schema),
        "instructionSha": _sha(instruction),
        "tools": sorted(_tool_names()),
    }


def _tool_names() -> set[str]:
    from mcp.server.fastmcp import FastMCP

    from wiki_mcp.tools import register
    from wiki_mcp.vaultfs import LocalVaultFS

    probe = FastMCP(name="fingerprint")
    register(probe, lambda ctx: "ALL", lambda key: LocalVaultFS(key))
    return set(probe._tool_manager._tools)


class Experiment:
    """One measurement's directory."""

    def __init__(self, slug: str):
        self.slug = slug
        self.dir = EXPERIMENTS / slug

    # ----- paths ------------------------------------------------------------

    @property
    def data(self) -> Path:
        return self.dir / DATA

    @property
    def report_path(self) -> Path:
        return self.dir / REPORT

    @property
    def manifest_path(self) -> Path:
        return self.dir / MANIFEST

    @property
    def notes_path(self) -> Path:
        return self.dir / NOTES

    def exists(self) -> bool:
        return self.dir.is_dir()

    def has_result(self) -> bool:
        return self.report_path.is_file()

    # ----- lifecycle --------------------------------------------------------

    def start(self, *, runtime: str, model: str | None, scope: str,
              corpus: list[str], purpose: str, dry_run: bool = False) -> dict:
        """Create the directory and write the manifest. Refuses to overwrite.

        A finished measurement cannot be reproduced — the model is not
        deterministic and the run costs an hour — so clobbering one is the kind of
        mistake that has no undo.
        """
        if self.has_result():
            raise FileExistsError(
                f"experiments/{self.slug}/{REPORT} 가 이미 있다. 측정을 덮어쓸 수 없다 — "
                "다른 이름을 쓰거나 그 디렉터리를 직접 옮긴다."
            )
        self.data.mkdir(parents=True, exist_ok=True)
        manifest = {
            "slug": self.slug,
            "startedAt": datetime.now().isoformat(timespec="seconds"),
            "runtime": runtime,
            "model": model,
            "scope": scope,
            "corpus": corpus,
            "purpose": purpose,
            "dryRun": dry_run,
            "code": code_fingerprint(),
        }
        self.manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        if not self.notes_path.exists():
            self.notes_path.write_text(_notes_skeleton(manifest), encoding="utf-8")
        return manifest

    def finish(self, report: dict) -> None:
        self.report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        manifest = self.manifest()
        manifest["finishedAt"] = datetime.now().isoformat(timespec="seconds")
        self.manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    # ----- reading ----------------------------------------------------------

    def manifest(self) -> dict:
        if not self.manifest_path.is_file():
            return {}
        return json.loads(self.manifest_path.read_text(encoding="utf-8"))

    def report(self) -> dict:
        if not self.report_path.is_file():
            return {}
        return json.loads(self.report_path.read_text(encoding="utf-8"))


def _notes_skeleton(manifest: dict) -> str:
    corpus = manifest["corpus"]
    listed = ", ".join(corpus) if len(corpus) <= 4 else f"{len(corpus)}건"
    return f"""# {manifest['slug']}

- 런타임: {manifest['runtime']} · 모델: {manifest['model']}
- 범위: {manifest['scope']} · 코퍼스: {listed}
- guide: {manifest['code']['guideSha']} ({manifest['code']['guideChars']}자)

## 무엇을 재려 했나

{manifest['purpose']}

## 결과

(측정 후 채운다)

## 판정

(측정 후 채운다)
"""


def all_slugs() -> list[str]:
    if not EXPERIMENTS.is_dir():
        return []
    return sorted(
        d.name for d in EXPERIMENTS.iterdir()
        if d.is_dir() and not d.name.startswith("_")
    )


def resolve_root(experiment: str | None, root: str | None) -> Path:
    """Turn `--experiment` or `--root` into a storage root, or explain what exists.

    Shared by `backend_sim`, `compare` and `graph_api` so the three cannot drift on
    where an experiment lives.
    """
    if experiment:
        return Experiment(experiment).data
    if root:
        return Path(root).resolve()
    slugs = all_slugs()
    listed = "\n".join(f"  {s}" for s in slugs) or "  (없다)"
    raise SystemExit(f"--experiment 나 --root 가 필요하다. 있는 실험:\n{listed}")


def copy_from(source_slug: str, target: "Experiment") -> Path:
    """기존 실험의 `data/` 를 복사해 새 실험의 시작점으로 쓴다.

    누적 위키 위에서 돌리는 실험이 원본을 건드리면 안 된다. 12건 측정은 95분·$25 이고
    비결정적 모델이라 되돌릴 수 없다.
    """
    import shutil

    source = Experiment(source_slug)
    if not source.data.is_dir():
        raise SystemExit(
            f"없는 실험: {source_slug}\n있는 것: {', '.join(all_slugs())}")

    if target.data.exists():
        shutil.rmtree(target.data)
    shutil.copytree(source.data, target.data)

    manifest = target.manifest()
    manifest["copiedFrom"] = source_slug
    target.manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return target.data
