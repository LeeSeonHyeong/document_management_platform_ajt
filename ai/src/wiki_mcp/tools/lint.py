"""Deterministic hygiene checks. From lucas-llmwiki `mcp/tools/lint.py`.

This is what makes a single-turn ingest verifiable: the agent is told to clear
every `error` before it finishes, and the harness can gate on the same output
without a human reading pages.

Kept from upstream: frontmatter completeness, footnote hygiene (duplicate,
undefined, unused, not-at-tail), citation resolution, dangling links, orphan
pages, uncited sources, stale pages.

Added here, each from a measured failure:
  * `missing-category` — `wiki.wiki_category_id` is NOT NULL, and a page with no
    category never appears in the table of contents.
  * `footnote-in-frontmatter` — a model copied its opening sentence, marker and
    all, into `description`, which then renders as a literal `[^1]`.
  * `citation-location-not-found` / `citation-quote-not-found` — **the check
    FR-WIKI-001 and NFR-AI-002 actually need.** Upstream verified only that the
    cited file existed. Verifying the location, and the verbatim quote against
    the source text, is what turns "a footnote is present" into "the claim is
    supported". It was the first item on the earlier spike's unverified list.

Dropped: the tag/date index-consistency and citation-graph-edge checks, which
guarded against an index drifting from content. Here content is the only writer.

## 검사 범위 — 상류와 갈라지는 곳 (D5)

상류에서는 에이전트가 저장소 전체의 주인이었으므로 전부 검사하는 것이 맞았다.
**여기서는 라이브 층이 에이전트에게 읽기 전용이다** (`vaultfs/base.py`). 그리고 지시가
"마지막에 `lint` 를 부르고 `error` 를 전부 고친다" 다.

`FR-WIKI-002` v2.9 에서 문맥 5개 상한이 없어져 위키가 전량 실려 오면, 남이 만든
frontmatter·각주가 error 로 쏟아지고 에이전트가 그것을 고치려 든다. 고칠 수도 없다 —
하이드레이션은 위키를 전부 올리지만 원본문서는 **이번 요청의 것만** 올리므로 다른 문서를
인용하는 기존 각주는 구조적으로 풀리지 않는다. 유일한 「수정」은 남의 각주를 지우는 것이다.

그래서 편집 세션에서는 검사 범위를 이렇게 나눈다.

  * **작업 층** — 전부 검사한다. 이번 작업이 쓴 것이고 에이전트가 고칠 수 있다
  * **라이브 전용** — 내용 검사를 하지 않는다. 내용이 바뀌지 않았으므로 새로 깨질 수 없다
  * 예외 하나 — 이번 작업이 페이지를 지우거나 병합해서 **들어오는 링크를 깼으면** 보여
    준다. 이번 작업이 만든 문제다. 반대로 나가는 링크가 원래부터 깨져 있던 것은 대상이
    아니다 — 그것은 남의 페이지를 고치라는 요구가 된다
  * `uncited-source` 는 유지한다. 이번 입력이 어느 페이지에도 반영되지 않은 것을 잡는
    유일한 검사다 (`_uncited` 주석)

읽기 전용 세션(`local_server.py` 를 `--job-id` 없이 띄운 개발 도구)은 작업 층이 없으므로
좁히지 않는다. 좁히면 아무것도 검사하지 않는 도구가 된다.

**반영 게이트는 이 범위와 별개다.** `api/session.py` 가 `collect()` 로 구조화된 결과를
받아 자기 기준으로 막는다 — 범위를 좁히는 것이지 게이트를 느슨하게 하는 것이 아니다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from typing import Literal

from mcp.server.fastmcp import Context, FastMCP

from wiki_mcp.services.footnotes import legacy_footnote_labels
from wiki_mcp.vaultfs import INDEX_ADDRESS, VaultError, VaultFS

from .helpers import MATCH_ALL, glob_match, label
from .references import parse_citation, parse_wiki_links
from .write import (
    extract_frontmatter_field,
    extract_frontmatter_tags,
    extract_metadata,
    is_footnote_suffix_line,
    parse_frontmatter,
)

_FOOTNOTE_DEF_RE = re.compile(r"^\[\^([^\]]+)\]:\s*(.+)$", re.MULTILINE)

# 편집 세션에서 목차에 대해 `warn` 으로 내리는 코드들. Spring 이 주는 목차 본문에
# frontmatter 가 없어 이 세션 안에서는 사라지지 않는 것들이다 (`_demote_index_frontmatter`).
# 반영 게이트가 무시하던 목록(`wiki_api/session.py::_INDEX_FRONTMATTER_CODES`)을 여기로
# 옮겼다 — 같은 판정이 두 곳에 있으면 한쪽이 낡는다.
_INDEX_FRONTMATTER_CODES = frozenset({
    "missing-frontmatter", "missing-title", "missing-tags",
    "missing-category", "footnote-in-frontmatter",
})
_FOOTNOTE_ANY_RE = re.compile(r"\[\^([^\]]+)\]")
_MAX_PER_GROUP = 40

# 표 구분선(`|---|:--:|`). 데이터 행을 셀 때 빼야 한다.
_TABLE_RULE_RE = re.compile(r"^[\s|:-]+$")
# 표 앞에서 각주를 찾아볼 범위. 표를 소개하는 문장은 바로 위 문단에 있다 — 그보다 멀리
# 보면 페이지 어딘가의 무관한 각주를 근거로 착각한다.
_TABLE_LEAD_IN_LINES = 3

# Below this a "location" is too short to check without false positives.
_MIN_LOCATION_CHARS = 2
# A quote shorter than this is a fragment, not a claim; matching it proves little
# and failing to match it would be noise.
_MIN_QUOTE_CHARS = 8

# 원본이 스크랩 출처마다 곧은/굽은 따옴표를 섞어 쓰는 경우가 실측으로 확인됐다
# (2026-08-02, 08-compensation.md: 같은 문서 안에 `won't`·`won't`가 같이 나온다).
# 모델이 의미는 맞게 인용해도 따옴표 문자 하나 때문에 `citation-quote-not-found`가
# 나는 걸 막으려고 비교 전에 둘 다 곧은 따옴표로 접는다. 마크다운 강조(`_..._`,
# `*...*`)도 원문 핵심 단어에 박혀 있는 경우가 있어 같은 이유로 벗겨낸다 — 강조
# 여부는 서식이지 인용의 정확성과 무관하다.
_QUOTE_FOLD = str.maketrans({
    "‘": "'", "’": "'",
    "“": '"', "”": '"',
})
# 백틱(인라인 코드)도 같은 이유로 벗긴다. 원문이 채널명·도메인·명령어를 `#private-xxxxx`
# 처럼 코드로 감싸는 경우가 잦은데(하네스 실측 2026-08-03, 12-communication.md 에서 2건),
# 모델은 그 백틱을 빼고 인용해 문자 그대로 달라져 `citation-quote-not-found` 가 났다.
# 코드로 감쌌는지는 서식이지 인용의 정확성과 무관하다.
_EMPHASIS_RE = re.compile(r"[_*`]")
# 원문 문장 중간에 마크다운 링크(`[글자](주소)`)가 박혀 있는 경우도 잦다(하네스 실측,
# 2026-08-02~03 — 04-benefits.md 한 문서에서만 5번). guide.py로 "링크 문법까지 그대로
# 옮기라"고 지시했지만 반복 관찰돼, 따옴표·강조와 같은 이유로 코드에서도 정규화한다 —
# 링크로 감쌌는지는 서식이지 인용의 정확성과 무관하다. 대상 텍스트만 남기고 `(주소)`는 버린다.
_LINK_RE = re.compile(r"\[([^\]]*)\]\([^)]*\)")


def _fold_quote_marks(text: str) -> str:
    return _EMPHASIS_RE.sub("", _LINK_RE.sub(r"\1", text).translate(_QUOTE_FOLD))

CheckScope = Literal["all", "wiki", "sources"]


@dataclass(frozen=True)
class LintIssue:
    severity: Literal["error", "warn"]
    code: str
    address: str
    message: str
    # 구조화된 세부 정보. 소비자가 메시지 문장을 정규식으로 되파싱하지 않게 하려고 둔다 —
    # `api/session.py` 가 그것을 하고 있었고, 여기 문장을 다듬으면 그쪽 게이트가 조용히
    # 열리는 구조였다.
    footnote: str | None = None      # 각주 관련 코드에서 그 라벨
    link_target: str | None = None   # `dangling-link` 에서 가리키던 주소


class LintHandler:
    def __init__(self, fs: VaultFS, scope: dict):
        self.fs = fs
        self.scope_id = str(scope["id"])
        self.scope_key = scope["scope_key"]
        # 편집 세션인가. `local_server.py` 를 `--job-id` 없이 띄우면 작업 층이 없는
        # 읽기 전용 개발 도구이고, 그때는 좁히지 않는다 (모듈 주석 「검사 범위」).
        self._editing = bool(getattr(fs, "job_id", None))

    async def run(self, pattern: str = "*", check_scope: CheckScope = "all",
                  include_graph: bool = True) -> str:
        """에이전트가 읽는 보고서. 범위는 모듈 주석의 규칙을 따른다."""
        issues, checked = await self._analyse(pattern, check_scope, include_graph)
        if checked is None:
            return f"`{pattern}`에 해당하는 것이 {self.scope_key} 범위에 없다."
        return self._report(issues, checked)

    async def collect(self, pattern: str = "*", check_scope: CheckScope = "all",
                      include_graph: bool = True) -> list[LintIssue]:
        """구조화된 결과. 반영 게이트(`api/session.py`)가 쓴다.

        `run()` 과 같은 검사를 돌리고 보고서로 만들지 않는다. 게이트가 문자열을 되파싱하면
        (a) `_MAX_PER_GROUP` 절단에 걸려 뒤쪽 항목을 못 보고 (b) 여기 문장을 다듬는 순간
        조용히 열린다. 둘 다 실제로 있었던 문제다.
        """
        issues, _ = await self._analyse(pattern, check_scope, include_graph)
        return issues

    async def _analyse(self, pattern: str, check_scope: CheckScope,
                       include_graph: bool) -> tuple[list[LintIssue], list[dict] | None]:
        docs = await self.fs.list_documents(self.scope_id, with_content=True)
        selected = self._select(docs, pattern, check_scope)
        if not selected:
            return [], None

        wiki_docs = [d for d in docs if d["kind"] in ("page", "index")]
        # 이번 작업이 사라지게 만든 주소. 라이브 페이지에서 볼 것은 이것뿐이다.
        removed = await self._removed_addresses()

        issues: list[LintIssue] = []
        for doc in selected:
            if doc["kind"] not in ("page", "index"):
                continue
            if self._is_this_jobs_work(doc):
                issues.extend(await self._lint_page(doc, wiki_docs, include_graph))
            else:
                issues.extend(self._lint_links_we_broke(doc, removed))

        if include_graph:
            issues.extend(i for i in await self._uncited() if self._matches(i.address, pattern))
            if check_scope in ("all", "wiki"):
                issues.extend(i for i in await self._stale() if self._matches(i.address, pattern))

        return issues, selected

    def _is_this_jobs_work(self, doc: dict) -> bool:
        return not self._editing or doc.get("layer") == "work"

    async def _legacy_labels(self, address: str, content: str) -> set[str]:
        """이 페이지에서 이번 작업 전부터 있던 각주 라벨.

        **편집 세션에서만 판정한다.** 작업 층이 없는 개발 도구 세션(`--job-id` 없이 띄운
        `local_server`)에서는 라이브가 곧 현재라 모든 각주가 「원래 있던 것」이 돼 미해결 인용이
        전부 강등된다. 그쪽은 사람이 읽는 진단이므로 그대로 `error` 를 내야 한다.
        """
        if not self._editing:
            return set()
        return await legacy_footnote_labels(self.fs, self.scope_id, address, content)

    async def _removed_addresses(self) -> set[str]:
        """이번 작업이 지우거나 병합해 없앤 주소.

        `list_documents` 는 `visible_documents` 를 보므로 묘비가 된 주소는 거기 없다.
        `pending_changes` 만이 「있었는데 우리가 없앴다」를 안다.
        """
        if not self._editing:
            return set()
        return {change["address"]
                for change in await self.fs.pending_changes(self.scope_id)
                if change.get("type") in ("remove", "merge")}

    # ----- selection --------------------------------------------------------

    def _select(self, docs: list[dict], pattern: str, check_scope: CheckScope) -> list[dict]:
        if check_scope == "wiki":
            docs = [d for d in docs if d["kind"] in ("page", "index")]
        elif check_scope == "sources":
            docs = [d for d in docs if d["kind"] == "source"]
        return [d for d in docs if self._matches(d["address"], pattern)]

    def _matches(self, address: str, pattern: str) -> bool:
        return pattern in MATCH_ALL or glob_match(address, pattern)

    # ----- per page ---------------------------------------------------------

    async def _lint_page(self, doc: dict, wiki_docs: list[dict],
                         include_graph: bool) -> list[LintIssue]:
        address = doc["address"]
        content = doc.get("content") or ""
        meta = parse_frontmatter(content)

        issues = self._demote_index_frontmatter(address,
                                                self._lint_frontmatter(address, meta))
        issues += self._lint_footnotes(address, content)
        issues += await self._lint_citations(address, content)
        issues += self._lint_uncited_tables(address, content)
        issues += self._lint_links(address, content, wiki_docs)
        if include_graph and address != INDEX_ADDRESS:
            issues += await self._lint_orphan(doc, len(wiki_docs))
        return issues

    # 이보다 작은 표는 알리지 않는다. 한두 행짜리 표까지 걸면 소음만 늘고, 그런 표는 대개
    # 앞 문단의 각주가 이미 덮는다.
    _TABLE_ROWS_WORTH_A_CITATION = 5

    def _lint_uncited_tables(self, address: str, content: str) -> list[LintIssue]:
        """표에 근거가 하나도 없으면 알린다.

        `_lint_citations` 는 **있는 각주가 원문과 맞는지**만 본다. 그래서 사실이 가장 밀집된
        곳(표)에 각주가 0개여도 통과했다 — 실측(하네스 13장)에서 26행·17행짜리 표가 각주 0으로
        `error 0건` 을 받았다. 근거 기반 생성(NFR-AI-002)이 표에서 비어 있던 것이다.

        **`warn` 이다.** `error` 로 하면 에이전트가 표 행마다 각주를 맞추려 돌다 턴 상한에
        걸린다 — 인용문 리터럴 일치를 `error` 로 강제했을 때 실제로 그랬다(2026-08-02 job 21:
        error 0인 채로 40턴·$2.76 소진). `guide` 는 「표를 소개하는 문장에 각주 하나」를
        요구하므로, 그 하나가 있으면 이 검사는 조용하다.
        """
        issues: list[LintIssue] = []
        for lead_in, block in _table_blocks(content):
            data_rows = [row for row in block
                         if not _TABLE_RULE_RE.match(row.strip())]
            # 첫 행은 헤더다
            if len(data_rows) - 1 < self._TABLE_ROWS_WORTH_A_CITATION:
                continue
            window = "\n".join(lead_in) + "\n" + "\n".join(block)
            if _FOOTNOTE_ANY_RE.search(window):
                continue
            issues.append(LintIssue(
                "warn", "table-without-citation", address,
                f"표({len(data_rows) - 1}행)에 근거 각주가 없다 — 표를 소개하는 문장에 "
                f"각주 하나를 달아 어느 절에서 온 표인지 밝힌다",
            ))
        return issues

    def _lint_frontmatter(self, address: str, meta: dict) -> list[LintIssue]:
        if not meta:
            return [LintIssue("error", "missing-frontmatter", address, "frontmatter가 없다")]

        issues: list[LintIssue] = []
        title = extract_frontmatter_field(meta, "title")
        description = extract_frontmatter_field(meta, "description")
        fm_date, _ = extract_metadata(meta)
        tags = extract_frontmatter_tags(meta)
        category = extract_frontmatter_field(meta, "category")

        if not title:
            issues.append(LintIssue("error", "missing-title", address, "title이 없다"))
        if not description:
            issues.append(LintIssue("warn", "missing-description", address, "description이 없다"))
        elif _FOOTNOTE_ANY_RE.search(description):
            issues.append(LintIssue("error", "footnote-in-frontmatter", address,
                                    "description에 각주 표시가 들어 있다"))
        if not fm_date:
            issues.append(LintIssue("warn", "missing-date", address, "date가 없다"))
        if tags is None:
            issues.append(LintIssue("error", "missing-tags", address, "tags가 없다"))
        elif len(tags) < 2:
            issues.append(LintIssue("warn", "too-few-tags", address, "tags를 2개 이상 단다"))
        if not category:
            issues.append(LintIssue("error", "missing-category", address,
                                    "category가 없다 — 목차에 나오지 않는다"))
        return issues

    def _demote_index_frontmatter(self, address: str,
                                  issues: list[LintIssue]) -> list[LintIssue]:
        """편집 세션에서 목차의 frontmatter 계열은 `warn` 으로 내린다.

        연동 경로의 Spring 하이드레이션은 이제 `index.md` 를 아예 넣지 않지만, 개발 도구
        경로(`LocalVaultFS`/`bootstrap_scope`)는 여전히 frontmatter 없는 `index.md` 를
        만든다. 그래서 이 오류들은 에이전트가 무엇을 해도 이 세션 안에서 사라지지 않고,
        반영 게이트도 이미 무시한다. `error` 로 두면 에이전트가 목차를 반복해 고치며 턴을
        쓴다 (실측 2026-08-05: 두 번 고쳐도 안 없어졌다).

        주소 한 곳에만 걸리는 판정이라 인용 쪽(`unresolved-citation-legacy`)처럼 코드를 새로
        나누지 않는다. 게이트는 `error` 만 막으므로 심각도를 내리는 것으로 충분하다.
        """
        if not self._editing or address != INDEX_ADDRESS:
            return issues
        return [issue if issue.code not in _INDEX_FRONTMATTER_CODES
                else replace(issue, severity="warn") for issue in issues]

    def _lint_footnotes(self, address: str, content: str) -> list[LintIssue]:
        issues: list[LintIssue] = []
        def_matches = list(_FOOTNOTE_DEF_RE.finditer(content))
        defined = [m.group(1) for m in def_matches]
        # `[^n]:` 뒤에 콜론이 온다고 정의로 보면, 문장 중간에서 각주 뒤에 목록을
        # 여는 등 우연히 콜론이 이어지는 정상적인 본문 사용을 오탐한다(실측,
        # 2026-08-02 하네스 — 각주 바로 뒤 콜론으로 목록을 여는 문장에서
        # `unused-footnote-definition` 오탐). 줄 시작이라는 위치로만 정의를 가른다.
        def_starts = {m.start() for m in def_matches}
        used = [m.group(1) for m in _FOOTNOTE_ANY_RE.finditer(content)
               if m.start() not in def_starts]

        for fid in sorted({f for f in defined if defined.count(f) > 1}, key=self._sort_key):
            issues.append(LintIssue("error", "duplicate-footnote", address,
                                    f"각주 `^{fid}`가 두 번 이상 정의됐다"))
        for fid in sorted(set(used) - set(defined), key=self._sort_key):
            issues.append(LintIssue("error", "footnote-without-definition", address,
                                    f"각주 `^{fid}`를 썼는데 정의가 없다"))
        for fid in sorted(set(defined) - set(used), key=self._sort_key):
            issues.append(LintIssue("warn", "unused-footnote-definition", address,
                                    f"각주 `^{fid}` 정의가 쓰이지 않는다"))
        if self._footnotes_mid_document(content):
            issues.append(LintIssue("warn", "footnotes-not-at-tail", address,
                                    "각주 정의는 문서 끝에 모은다"))
        return issues

    async def _lint_citations(self, address: str, content: str) -> list[LintIssue]:
        """Every footnote resolves to a source, a location in it, and — when the
        footnote quotes the source — to that exact text.

        **원본문서를 못 찾은 각주는 그게 이번 작업 전부터 있던 것인지 가른다.** 하이드레이션은
        위키 페이지 전부를 올리지만 원본문서는 이번 요청의 것만 올리므로, 다른 문서를 인용하는
        기존 각주는 세션 안에서 구조적으로 풀리지 않는다 — 에이전트는 원본문서를 만들 수 없어
        고칠 수단이 없다. `error` 로 내면 「error 0까지 끝내지 않는다」는 지시와 맞물려 확정적
        루프가 된다 (실측: `services/footnotes.py` docstring).
        """
        issues: list[LintIssue] = []
        legacy = await self._legacy_labels(address, content)
        for footnote, raw in _FOOTNOTE_DEF_RE.findall(content):
            parsed = parse_citation(raw)
            target = await self.fs.find_source(self.scope_id, parsed["name"])
            if not target:
                if footnote in legacy:
                    issues.append(LintIssue(
                        "warn", "unresolved-citation-legacy", address,
                        f"각주 `^{footnote}`가 가리키는 `{parsed['name']}`이 이 작업에 올라오지 "
                        f"않았다 — 이번 작업 전부터 있던 각주이니 고칠 수 없다. 그대로 둔다",
                        footnote=footnote,
                    ))
                    continue
                issues.append(LintIssue(
                    "error", "unresolved-citation", address,
                    f"각주 `^{footnote}`가 `{parsed['name']}`을 가리키는데 그런 원본문서가 없다",
                    footnote=footnote,
                ))
                continue

            source_text = (target.get("content") or "")
            if not parsed["location"] and parsed["page"] is None and not parsed["quote"]:
                issues.append(LintIssue(
                    "warn", "citation-without-location", address,
                    f"각주 `^{footnote}`에 위치가 없다 — 절 제목이나 쪽 번호를 붙인다",
                ))

            problem = await self._check_page(target, parsed)
            if problem:
                issues.append(LintIssue("error", "citation-location-not-found", address,
                                        f"각주 `^{footnote}`: {problem}"))
                continue
            if self._location_missing(parsed["location"], source_text):
                issues.append(LintIssue(
                    "error", "citation-location-not-found", address,
                    f"각주 `^{footnote}`: `{parsed['name']}`에서 `{parsed['location']}`을 "
                    "찾을 수 없다",
                ))
            if self._quote_missing(parsed["quote"], source_text):
                # warn, not error: 위치(`citation-location-not-found`)가 이미 날조를 막는
                # 최소선이다. 인용문 리터럴 일치까지 강제하면 에이전트가 정당한 주장에
                # 딱 맞는 문장을 못 찾고 계속 다른 표현을 시도하다 턴 상한(GraphRecursionError)
                # 에 걸려 작업 전체가 실패하는 사례가 실측으로 확인됐다
                # (2026-08-02, ai/docs/findings/2026-08-02-wiki-e2e-stability-test.md).
                issues.append(LintIssue(
                    "warn", "citation-quote-not-found", address,
                    f"각주 `^{footnote}`: 인용문 \"{parsed['quote'][:40]}\"이 "
                    f"`{parsed['name']}` 원문에 없다",
                ))
        return issues

    async def _check_page(self, target: dict, parsed: dict) -> str | None:
        page = parsed["page"]
        if page is None:
            return None
        page_count = target.get("page_count") or 0
        if page_count and page > page_count:
            return f"`{parsed['name']}`은 {page_count}쪽까지인데 {page}쪽을 가리킨다"
        if page_count and not await self.fs.get_source_pages(target["id"], [page]):
            return f"`{parsed['name']}`의 {page}쪽 데이터가 없다"
        return None

    def _location_missing(self, location: str, source_text: str) -> bool:
        """A section name may be worded differently, so require every word to be
        absent before calling it wrong. A false error here teaches the agent to
        ignore lint."""
        if len(location) < _MIN_LOCATION_CHARS or not source_text:
            return False
        haystack = source_text.lower()
        if location.lower() in haystack:
            return False
        words = [w for w in re.split(r"[\s·,]+", location) if len(w) >= _MIN_LOCATION_CHARS]
        return bool(words) and not all(w.lower() in haystack for w in words)

    def _quote_missing(self, quote: str | None, source_text: str) -> bool:
        """Verbatim match, whitespace-normalised.

        Not word-by-word like a location: a quote claims to be the source's own
        words. Ellipses are how a model elides, so each segment is checked
        separately.
        """
        if not quote or len(quote) < _MIN_QUOTE_CHARS or not source_text:
            return False
        haystack = _fold_quote_marks(re.sub(r"\s+", " ", source_text)).lower()
        for segment in re.split(r"\s*(?:\.\.\.|…)\s*", quote):
            segment = _fold_quote_marks(re.sub(r"\s+", " ", segment)).strip().lower()
            if len(segment) < _MIN_QUOTE_CHARS:
                continue
            if segment not in haystack:
                return True
        return False

    def _lint_links(self, address: str, content: str, wiki_docs: list[dict]) -> list[LintIssue]:
        """Body links resolve, and never leave this scope.

        A link is safe only because every page in one scope has identical
        visibility. One that left the scope would leak the title of a page the
        reader cannot see (FR-ACL-006, NFR-SEC-003), and a body link cannot be
        filtered per viewer. One server serves one scope, so a link resolving to
        nothing here is either broken or cross-scope — both errors.
        """
        known = {d["address"] for d in wiki_docs}
        issues: list[LintIssue] = []
        for target in parse_wiki_links(content):
            if target not in known:
                issues.append(self._dangling(address, target))
        return issues

    def _lint_links_we_broke(self, doc: dict, removed: set[str]) -> list[LintIssue]:
        """라이브 전용 페이지에서 유일하게 보는 것: **이번 작업이 깬 들어오는 링크.**

        내용 검사를 하지 않는 이유는 내용이 바뀌지 않았다는 것이다 — 새로 깨질 수 없고,
        원래 깨져 있던 것은 이번 작업의 책임이 아니다. 그런데 이번 작업이 페이지를 지우거나
        병합하면 그것을 가리키던 링크는 **우리가** 깬 것이다. 그것만 보여 준다.

        나가는 링크를 지운 경우는 대상이 아니다. 그러려면 남의 페이지 본문을 고쳐야 하고,
        그 수정이 작업 층에 쌓여 이번 문서와 무관한 변경으로 반영된다.
        """
        if not removed:
            return []
        content = doc.get("content") or ""
        return [self._dangling(doc["address"], target)
                for target in parse_wiki_links(content) if target in removed]

    @staticmethod
    def _dangling(address: str, target: str) -> LintIssue:
        return LintIssue(
            "error", "dangling-link", address,
            f"본문 링크 `{target}`가 이 범위의 어떤 페이지도 가리키지 않는다",
            link_target=target)

    async def _lint_orphan(self, doc: dict, wiki_count: int) -> list[LintIssue]:
        if wiki_count <= 1:
            return []
        if await self.fs.get_backlinks(self.scope_id, doc["address"]):
            return []
        # 「목차에서 닿을 수 없다」로 판정하던 것을 바꿨다. 목차는 이제 하이드레이션되지 않고
        # (S15P11B106-280 이후 Spring 이 DB 로 그린다) 항상 공간 전체를 담으므로 목차 도달성은
        # 늘 참이다. 남는 질문은 「다른 페이지가 나를 링크하나」이고, 그것은 여전히 중요하다 —
        # 본문 링크가 「관련 위키」 관계의 유일한 입력원이다 (`guide.py`).
        return [LintIssue("warn", "orphan-page", doc["address"],
                          "다른 페이지 중 아무도 이 페이지를 링크하지 않는다 — "
                          "관련 페이지가 있으면 본문에서 링크한다")]

    # ----- graph-wide -------------------------------------------------------

    async def _uncited(self) -> list[LintIssue]:
        """A document cited by no page is knowledge that never landed — an error,
        not a warning. It is the failure a prompt variant produced when it
        dropped a whole document silently."""
        return [
            LintIssue("error", "uncited-source", r["address"],
                      f"`{r.get('original_file_name')}`을 인용하는 위키 페이지가 없다")
            for r in await self.fs.find_uncited_sources(self.scope_id)
        ]

    async def _stale(self) -> list[LintIssue]:
        return [
            LintIssue("warn", "stale-page", r["address"],
                      f"{r.get('stale_since') or '?'} 이후 오래됐을 수 있다")
            for r in await self.fs.find_stale_pages(self.scope_id)
            if r["address"] != INDEX_ADDRESS
        ]

    # ----- report -----------------------------------------------------------

    def _report(self, issues: list[LintIssue], checked: list[dict]) -> str:
        # 여기 문장은 에이전트가 다음 행동을 정하려고 읽는 내부 신호다 — 최종 답변에 그대로
        # 옮겨지는 사고가 있었으므로(관리자에게 "error 0건, warn 통과" 식으로 노출) 일부러
        # "error"·"warn" 같은 개발 용어를 쓰지 않는다. 의미는 severity 필드가 그대로 갖고 있다.
        if not issues:
            return f"**lint 통과** — {self.scope_key} 범위, {len(checked)}건 검사."
        errors = [i for i in issues if i.severity == "error"]
        warnings = [i for i in issues if i.severity == "warn"]
        lines = [
            f"**lint {len(issues)}건** — {self.scope_key} 범위 "
            f"(반드시 고칠 것 {len(errors)}건, 참고용 {len(warnings)}건; {len(checked)}건 검사)."
        ]
        if errors:
            lines.append("\n**반드시 고칠 것** — 끝내기 전에 모두 고친다")
            lines += self._lines(errors)
        if warnings:
            lines.append("\n**참고용(고치지 않아도 됨)**")
            lines += self._lines(warnings)
        if not errors:
            # 반드시 고칠 것이 없으면 여기서 끝이다 — 이 문장이 없으면 모델이 참고용 목록도
            # "고칠 것"으로 읽고 같은 각주를 표현만 바꿔가며 반복 편집하다 턴 상한
            # (GraphRecursionError)에 걸린다 (2026-08-02 job 21 실측, 4번째 재현: 반드시 고칠
            # 것 0건인 채로 40턴·$2.76 소진).
            lines.append(
                "\n**반드시 고칠 것이 없으니 이걸로 끝이다.** 위 참고용 항목은 참고만 한다 — "
                "고치려고 다시 `edit`·`lint`를 부르지 않는다. 지금 상태로 작업을 마친다."
            )
        return "\n".join(lines)

    def _lines(self, issues: list[LintIssue]) -> list[str]:
        out = [f"- [{i.code}] `{i.address}` — {i.message}" for i in issues[:_MAX_PER_GROUP]]
        if len(issues) > _MAX_PER_GROUP:
            out.append(f"- ... {len(issues) - _MAX_PER_GROUP}건 더")
        return out

    def _footnotes_mid_document(self, content: str) -> bool:
        lines = content.rstrip().splitlines()
        for idx, line in enumerate(lines):
            if _FOOTNOTE_DEF_RE.match(line):
                return not all(is_footnote_suffix_line(s) for s in lines[idx + 1:])
        return False

    def _sort_key(self, value: str) -> tuple[int, str]:
        return (0, f"{int(value):08d}") if value.isdigit() else (1, value)


def _table_blocks(content: str) -> list[tuple[list[str], list[str]]]:
    """마크다운 표마다 `(앞 몇 줄, 표 줄들)`.

    앞 몇 줄을 함께 돌려주는 이유는 표를 소개하는 문장에 각주가 붙기 때문이다 — `guide` 가
    요구하는 모양이 그것이고, 표 안만 보면 그 각주를 놓친다.
    """
    lines = content.splitlines()
    blocks: list[tuple[list[str], list[str]]] = []
    index = 0
    while index < len(lines):
        if not lines[index].lstrip().startswith("|"):
            index += 1
            continue
        start = index
        while index < len(lines) and lines[index].lstrip().startswith("|"):
            index += 1
        lead_in = [line for line in lines[max(0, start - _TABLE_LEAD_IN_LINES):start]
                   if line.strip()]
        blocks.append((lead_in, lines[start:index]))
    return blocks


def register(mcp: FastMCP, get_scope_key, fs_factory) -> None:

    @mcp.tool(
        name="lint",
        description=(
            "위키를 기계적으로 점검한다.\n\n"
            "검사 항목: frontmatter 필수 항목(title·tags·category), 각주 위생(중복·정의 없음·"
            "안 쓰임·끝에 안 모임), **인용이 실제 원본문서와 그 안의 위치를 가리키는지, 인용문이 "
            "원문에 그대로 있는지**, 본문 링크가 이 범위 안에서 해결되는지, 고아 페이지, "
            "인용 안 된 원본문서, 오래된 페이지.\n\n"
            "**검사 대상은 이번 작업이 쓴 페이지다.** 이번에 건드리지 않은 기존 페이지의 "
            "내용은 검사하지 않는다 — 그 페이지들이 인용하는 원본문서는 이 작업에 실려 오지 "
            "않아 확인할 수 없고, 고치는 것도 이 작업의 범위가 아니다. 단 **이번에 페이지를 "
            "지우거나 병합해서 기존 페이지의 링크를 깼으면** 그것은 보고한다.\n\n"
            "고친 페이지에 **이번 작업 전부터 있던** 각주가 남아 있고 그 원본문서가 이 작업에 "
            "올라오지 않았으면, 그것은 `error`가 아니라 참고용으로 나온다 — 고칠 수단이 없으니 "
            "그대로 둔다.\n\n"
            "**작업을 끝내기 전에 반드시 부르고 `error`는 전부 고친다.** `warn`은 이유가 있으면 남긴다.\n"
            '`path`로 좁힌다: `*`, `pages/*`, `pages/a3f2c1d4.md`'
        ),
    )
    async def lint(ctx: Context, scope: str, path: str = "*",
                   check_scope: CheckScope = "all", include_graph: bool = True) -> str:
        fs = fs_factory(get_scope_key(ctx))
        row = await fs.resolve_scope(scope)
        if not row:
            return f"범위 '{scope}'를 찾을 수 없다."
        try:
            return await LintHandler(fs, row).run(path, check_scope, include_graph)
        except VaultError as exc:
            return f"오류: {exc}"
