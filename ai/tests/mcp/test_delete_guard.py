"""`delete` 의 대량 삭제 상한 (2026-08-05 도구 검토).

`MATCH_ALL`(`*`·`**`·`**/*`)만 막고 있었다. **`pages/*` 는 그 집합에 없어서 통과했고**,
원본문서와 `index.md` 만 거부되므로 그 한 번이 범위의 위키를 전부 묘비 처리했다 — 되돌릴
수단이 없는 도구다(`tools/delete.py` 모듈 docstring, FR-WIKI-006).

게이트에 2차 방어선이 있기는 하다(목차가 지워진 주소를 가리키면 `dangling-link` 가 반영을
막는다). 하지만 그것은 「잡 전체가 실패한다」는 뜻이고, 에이전트가 목차까지 정리하면 그대로
반영된다. 도구 쪽에서 개수로 막는다.

설명에서 `pages/*` 예시를 뺀 것도 같은 이유다 — **위험한 형태를 도구가 가르치고 있었다.**
"""

import pytest

from wiki_mcp.tools.delete import MAX_GLOB_DELETE, DeleteHandler

SCOPE_ROW = {"id": "scope-1", "scope_key": "D1-D2"}


class FakeFS:
    def __init__(self, count):
        self.docs = [{"address": f"pages/p{i:02d}.md", "title": f"페이지 {i}",
                      "kind": "page"} for i in range(count)]
        self.removed: list[str] = []

    async def list_documents(self, scope_id, with_content=False):
        return self.docs

    async def remove(self, scope_id, address):
        self.removed.append(address)
        return True


@pytest.mark.parametrize("pattern", ["*", "**", "**/*"])
async def test_the_whole_scope_is_still_refused_outright(pattern):
    fs = FakeFS(3)
    result = await DeleteHandler(fs, SCOPE_ROW).delete(pattern)
    assert "전체 삭제는 거부한다" in result
    assert fs.removed == []


async def test_a_glob_matching_more_than_the_cap_is_refused_and_listed():
    fs = FakeFS(MAX_GLOB_DELETE + 1)

    result = await DeleteHandler(fs, SCOPE_ROW).delete("pages/*")

    assert fs.removed == [], "상한을 넘으면 한 건도 지우지 않는다"
    assert f"{MAX_GLOB_DELETE + 1}건에 맞는다" in result
    # 무엇이 맞았는지 보여줘야 에이전트가 다음 호출을 좁힐 수 있다.
    assert "pages/p00.md" in result


async def test_a_glob_within_the_cap_still_works():
    """의도한 정리는 막지 않는다 — 상한은 오타를 막는 것이다."""
    fs = FakeFS(MAX_GLOB_DELETE)

    result = await DeleteHandler(fs, SCOPE_ROW).delete("pages/*")

    assert len(fs.removed) == MAX_GLOB_DELETE
    assert f"{MAX_GLOB_DELETE}건 제거" in result


def test_the_description_no_longer_teaches_the_dangerous_form():
    """설명이 `path="pages/*"` 를 예시로 들고 있었다 — 도구가 위험한 형태를 가르쳤다.

    파일 전문을 본다. 설명이 f-string 이라 리터럴로 뽑을 수 없고, 어차피 이 문자열은 이
    파일 안에서 설명 말고 나올 자리가 없다.
    """
    from pathlib import Path

    import wiki_mcp.tools.delete as module

    source = Path(module.__file__).read_text(encoding="utf-8")
    assert 'path="pages/*"' not in source
