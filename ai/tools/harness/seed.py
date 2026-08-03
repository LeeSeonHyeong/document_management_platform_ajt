"""하네스 vault(`tools/harness/vault/`)에 `experiments/corpus/`의 원본문서를 심는다.

Spring/MySQL 없이 `wiki_mcp` 도구·lint·guide.py를 반복 검증하기 위한 것이다 (독립
로컬 vault). `LocalVaultFS`가 만드는 색인·디렉터리 구조를 그대로 쓰므로, 프로덕션의
`FederatedVaultFS`가 내부적으로 타는 것과 같은 코드 경로다 — 차이는 카탈로그/본문을
파일에서 직접 읽느냐 Spring API로 당기느냐뿐.

Usage:
    uv run python tools/harness/seed.py 08-compensation.md
    uv run python tools/harness/seed.py --all
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
AI_ROOT = ROOT.parent.parent
sys.path.insert(0, str(AI_ROOT / "src"))

from wiki_mcp.vaultfs import LocalVaultFS  # noqa: E402
from wiki_mcp.vaultfs.local import bootstrap_scope, register_source  # noqa: E402
from wiki_mcp.vaultfs.rebuild import rebuild_index  # noqa: E402

VAULT_ROOT = ROOT / "vault"
CORPUS_ROOT = AI_ROOT / "experiments" / "corpus"
SCOPE_KEY = "ALL"


async def _seed(filenames: list[str]) -> None:
    VAULT_ROOT.mkdir(parents=True, exist_ok=True)
    await LocalVaultFS.open(VAULT_ROOT, SCOPE_KEY)
    await bootstrap_scope(SCOPE_KEY)

    for name in filenames:
        path = CORPUS_ROOT / name
        text = path.read_text(encoding="utf-8")
        document_id = path.stem
        await register_source(SCOPE_KEY, document_id, name, text)
        print(f"등록: {name} -> sources/{document_id}/parsed/content.md")

    await LocalVaultFS.close()

    # rebuild_index()는 자기가 연 연결을 안 닫는다 — 안 닫으면 aiosqlite 워커 스레드가
    # 안 죽어서 asyncio.run() 종료 후 인터프리터가 무한 대기한다.
    counts = await rebuild_index(VAULT_ROOT, SCOPE_KEY)
    await LocalVaultFS.close()
    print(f"색인 재생성: {counts}")


def main() -> None:
    parser = argparse.ArgumentParser(description="하네스 vault에 corpus 원본문서를 시드한다")
    parser.add_argument("files", nargs="*", help="experiments/corpus/ 안의 파일명")
    parser.add_argument("--all", action="store_true", help="corpus 전체를 시드한다")
    args = parser.parse_args()

    if args.all:
        filenames = sorted(p.name for p in CORPUS_ROOT.glob("*.md") if p.name != "README.md")
    elif args.files:
        filenames = args.files
    else:
        parser.error("파일명을 하나 이상 주거나 --all 을 쓴다")
        return

    asyncio.run(_seed(filenames))


if __name__ == "__main__":
    main()
