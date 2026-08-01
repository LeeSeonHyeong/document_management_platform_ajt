"""1단계 문맥 선택이 위키가 커지면 놓치는가 — 선택 단계만 따로 돌려 잰다.

**왜 이걸 재나.** 위키 변환을 호출 1회로 합치는(에이전트가 스스로 조회) 리팩토링의 근거는
「1단계 선택이 놓치면 에이전트가 회복할 방법이 없다」다. 그런데 기존 12문서 실측
(`2026-07-27-opus46-12docs`)에서는 **중복 페이지 0건 · 「자료가 없다」 발언 0건**이었다 —
문서 주제가 서로 다르고 위키가 20장이라 조건이 쉬웠다. 어려운 조건에서 실제로 놓치는지
확인하지 않으면 리팩토링 근거가 없다.

**어려운 조건을 만든다.** 위키 100장(`corpus-ko`)의 목차를 만들고, 정답을 미리 아는 문서
5건을 넣는다. 난이도는 「문서 제목과 목차 제목이 얼마나 겹치나」로 조절한다 — 실서비스의
실패 방식이 그것이다. 「휴가 이월」을 다루는 개정 문서가 들어왔을 때 목차에 「연차 휴가」로만
적혀 있으면 어휘가 안 겹친다.

**재는 것과 못 재는 것.** 이 스크립트는 1단계 선택의 재현율만 잰다. 「에이전트가 직접 읽으면
더 잘하나」는 재지 않는다 — 그건 두 경로를 다 돌려야 하고 예산이 든다.

돌리는 법:

    uv run python experiments/selection_ceiling.py            # 목차만 만들어 보여준다 (0원)
    uv run python experiments/selection_ceiling.py --run      # 모델을 부른다
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "src"))

CORPUS = HERE / "corpus-ko" / "pages"

# 정답을 미리 아는 문서 5건. 본문은 해당 페이지에서 따와 「개정판이 들어왔다」를 흉내 낸다.
# `expect` 는 **반드시 읽어야 하는** 페이지다 (사람이 판단한 정답).
CASES = [
    {
        "name": "1. 제목이 그대로 겹친다",
        "expect": ["annual-leave"],
        "document": "# 연차 휴가 규정 개정 (2026)\n\n"
                    "연차 휴가 산정 기준을 개정한다. 기존 규정의 이월 조건을 아래로 대체한다.\n"
                    "- 미사용 연차는 다음 해 3월까지 이월한다\n"
                    "- 반차는 오전·오후로만 나눈다\n",
    },
    {
        "name": "2. 주제는 같고 어휘가 다르다",
        "expect": ["annual-leave"],
        "document": "# 미사용 휴가 이월과 반일 근무 운영 세칙\n\n"
                    "미사용분의 다음 연도 이관 한도와 반일 단위 근무 신청 절차를 정한다.\n"
                    "이관 한도는 5일이며 3월 31일까지 사용하지 않으면 소멸한다.\n",
    },
    {
        "name": "3. 두 페이지에 걸친다",
        "expect": ["annual-leave", "communication"],
        "document": "# 휴가 중 연락 원칙\n\n"
                    "휴가 기간에는 응답 의무가 없다. 휴가 신청 시 담당자를 지정하고 "
                    "부재 사실을 팀 채널에 남긴다. 연차 산정과 채널 공지 규칙을 함께 따른다.\n",
    },
    {
        "name": "4. 목차에 없는 주제다 (아무것도 안 골라야 맞다)",
        "expect": [],
        "document": "# 사내 카페테리아 원두 교체 안내\n\n"
                    "8월부터 원두를 교체한다. 기존 블렌드 재고는 소진 후 종료한다.\n",
    },
    {
        "name": "5. 합성 잡음 페이지를 정확히 가리킨다",
        "expect": ["d004"],
        "document": "# 배포 승인 절차 보완\n\n"
                    "배포 승인 단계에 검토자 2인 확인을 추가한다. 기존 승인 흐름의 "
                    "단계 구분과 예외 처리는 그대로 둔다.\n",
    },
]


def _title_and_summary(path: Path) -> tuple[str, str]:
    """제목과 한 줄 요약. **frontmatter 를 먼저 걷어낸다** — 처음에 그것을 안 걷어서 요약이
    전부 `---` 로 채워졌고, 모델이 제목만 보고 고르게 되어 실서비스보다 정보가 적은 조건을
    재고 있었다. 실계약 목차에는 요약이 실린다."""
    text = path.read_text(encoding="utf-8")
    body = re.sub(r"^---\n.*?\n---\n", "", text, count=1, flags=re.S)
    heading = re.search(r"^#\s*(.+)$", body, re.M)
    title = heading.group(1).strip() if heading else path.stem
    prose = re.sub(r"^#{1,6}\s.*$", "", body, flags=re.M)          # 제목·소제목 제거
    first = next((line.strip() for line in prose.splitlines()
                  if line.strip() and not line.startswith(("-", "|", ">"))), "")
    return title, first[:70]


def build_index() -> tuple[str, dict[str, str], dict[str, str]]:
    """`corpus-ko` 100장으로 실서비스 모양의 목차를 만든다.

    **`wikiId` 는 숫자다.** 처음에 파일 이름(`annual-leave`)을 ID 로 썼다가 틀렸다 — Spring 이
    목차를 자기가 다시 렌더링하고(`WikiTransformationApplier.writeIndex` → `WikiIndex.render`)
    그 형식이 `pages/(\\d+).md` 만 허용한다. 즉 에이전트가 실제로 보는 목차의 링크는 항상
    숫자다. 파일 이름으로 재면 모델이 번호를 답해도 「목차에 없는 ID」로 버려져서, 선택
    성능이 아니라 내 실험의 형식 오류를 재게 된다.
    """
    lines = ["# 목차\n"]
    titles: dict[str, str] = {}
    stem_of: dict[str, str] = {}
    for number, path in enumerate(sorted(CORPUS.glob("*.md")), 1):
        title, summary = _title_and_summary(path)
        wiki_id = str(number)
        titles[wiki_id] = title
        stem_of[path.stem] = wiki_id
        lines.append(f"- [{title}](pages/{wiki_id}.md) — {summary}")
    return "\n".join(lines) + "\n", titles, stem_of


async def run_case(runtime, case: dict, index: str, titles: dict) -> dict:
    from agent_runtime.base import QUALITY, selection_instruction
    from wiki_api.selection import parse_selection

    instruction = selection_instruction(case["document"], index)
    result = await asyncio.to_thread(
        runtime.complete, [{"role": "user", "content": instruction}],
        tier=QUALITY, timeout=60)
    picked, reason = parse_selection(result.text, index)
    expected = set(case["expect"])
    got = set(picked)
    return {
        "name": case["name"],
        "expected": sorted(expected),
        "picked": picked,
        "hit": sorted(expected & got),
        "missed": sorted(expected - got),
        "extra": sorted(got - expected),
        "pickedTitles": [titles.get(i, i) for i in picked],
        "reason": reason[:120],
        "input_tokens": result.input_tokens,
        "output_tokens": result.output_tokens,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="모델을 실제로 부른다")
    parser.add_argument("--model", default="openai:gpt-4o-mini")
    args = parser.parse_args(argv)

    index, titles, stem_of = build_index()
    for case in CASES:
        case["expect"] = [stem_of[stem] for stem in case["expect"]]
    print(f"목차 {len(titles)}장 · {len(index):,}자 ({len(index.encode()):,}바이트)")
    for case in CASES:
        shown = [f"{i}({titles[i]})" for i in case["expect"]] or "없음"
        print(f"  {case['name']:32} 정답 {shown}")

    if not args.run:
        print("\n--run 을 주면 모델을 부른다. (선택 호출 5회)")
        return 0

    from agent_runtime import load_runtime
    from wiki_api.settings import ServerSettings, credential_table

    settings = ServerSettings(internal_api_key="x", runtime="deepagents",
                              model=args.model)
    runtime = load_runtime("deepagents", args.model,
                           credentials=credential_table(settings))

    rows = [asyncio.run(run_case(runtime, case, index, titles)) for case in CASES]
    print()
    for row in rows:
        mark = "OK " if not row["missed"] else "놓침"
        print(f"{mark} {row['name']:32} 고른 것 {row['picked']}")
        if row["missed"]:
            print(f"      놓친 정답: {row['missed']}")
        if row["extra"]:
            print(f"      군더더기: {row['extra'][:6]}")
        print(f"      이유: {row['reason']}")
    missed = sum(1 for r in rows if r["missed"])
    tokens = sum(r["input_tokens"] for r in rows)
    print(f"\n놓친 사례 {missed}/{len(rows)} · 입력 토큰 합계 {tokens:,}")
    (HERE / "selection_ceiling_result.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print("결과: experiments/selection_ceiling_result.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
