import pytest
import pytest_asyncio

JOB_ID = "9001"
SCOPE = "ALL"

GOOD_SOURCE = """\
# 인사규정

## 3장 휴가

연차는 입사일을 기준으로 산정한다. 이월은 다음 해 3월까지 가능하다.

## 4장 보상

기본급은 매년 1월에 조정한다.
"""


@pytest_asyncio.fixture
async def vault(tmp_path):
    """A live scope with index.md and one registered source, plus an open job."""
    from wiki_mcp.vaultfs import LocalVaultFS
    from wiki_mcp.vaultfs.local import bootstrap_scope, register_source

    scope_id = await LocalVaultFS.open(tmp_path, SCOPE, JOB_ID)
    await bootstrap_scope(SCOPE)
    await register_source(SCOPE, "101", "인사규정.pdf", GOOD_SOURCE)
    fs = LocalVaultFS(SCOPE, JOB_ID)
    yield tmp_path, scope_id, fs
    await LocalVaultFS.close()


@pytest.fixture
def scope_row(vault):
    _, scope_id, _ = vault
    return {"id": scope_id, "scope_key": SCOPE, "index_path": f"wiki/{SCOPE}/index.md"}
