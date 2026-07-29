"""챗봇 측정 하네스 — Spring 이 할 일을 대신한다.

**이 파일은 버리는 것이다.** 권한 검증, 목차 제공, 고른 ID 의 본문 읽기, 응답 저장은
백엔드의 일이다. Spring 이 생기면 사라진다.

`backend_sim.py` 의 `--via-api` 와 같은 자세를 따른다 — 측정 경로가 프로덕션 경로다.
계약 스키마·어댑터·타임아웃·응답 조립을 전부 실제로 통과한다.

**크레딧 방어가 이 파일의 절반이다.**

  * `--dry-run` — 호출 없이 조립된 요청 크기만 출력한다. 프롬프트 버그를 공짜로 잡는다
  * `--limit N` — 질문 2개로 먼저 확인하고 8개는 마지막 한 번
  * `--stage 1` — 1단계만. 2단계는 본문을 실어 비싸다
  * 재시도 없음 — 실패는 실패로 기록한다

사용:

    # 크레딧 0
    uv run python experiments/chat_sim.py --dry-run

    # 서버를 띄운 뒤
    AI_COMPLETION_LOG=/tmp/c.jsonl uv run python -m wiki_api.serve \\
      --runtime deepagents --internal-api-key k --port 8000
    uv run python experiments/chat_sim.py --stage 1 --limit 2 \\
      --api http://127.0.0.1:8000 --key k
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_PLAN = ROOT / "chat_questions.json"


def load_plan(path: str | Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _index_markdown(wiki_dir: str | Path) -> str:
    return (Path(wiki_dir) / "index.md").read_text(encoding="utf-8")


def _page_markdown(wiki_dir: str | Path, wiki_id: str) -> str:
    return (Path(wiki_dir) / "pages" / f"{wiki_id}.md").read_text(encoding="utf-8")


def _page_title(markdown: str, fallback: str) -> str:
    """프론트매터의 `title:` 또는 첫 `# ` 헤더. Spring 은 DB 에서 읽는다."""
    for line in markdown.split("\n")[:20]:
        stripped = line.strip()
        if stripped.startswith("title:"):
            return stripped.removeprefix("title:").strip().strip('"')
        if stripped.startswith("# "):
            return stripped.removeprefix("# ").strip()
    return fallback


def build_context_body(plan: dict, question: dict, wiki_dir: str | Path,
                       *, index: int) -> dict:
    """1단계 요청. **본문은 넣지 않는다** — 계약 정책이다."""
    summaries = [
        {k: v for k, v in schedule.items() if k != "content"}
        for schedule in plan["schedules"]
    ]
    return {
        "questionId": str(500 + index),
        "conversationId": f"chat-sim-{index}",
        "question": question["question"],
        "conversationMessages": [],
        "wikiIndexes": [{"scopeKey": "ALL",
                         "indexMarkdown": _index_markdown(wiki_dir)}],
        "scheduleSummaries": summaries,
    }


def build_answer_body(plan: dict, question: dict, wiki_dir: str | Path, *,
                      question_type: str, wiki_ids: list[str],
                      schedule_ids: list[str], index: int = 0) -> dict:
    """2단계 요청. Spring 이 하듯 고른 ID 의 본문을 파일에서 읽어 싣는다."""
    by_id = {s["scheduleId"]: s for s in plan["schedules"]}
    wikis = []
    for wiki_id in wiki_ids:
        body = _page_markdown(wiki_dir, wiki_id)
        wikis.append({"wikiId": wiki_id, "title": _page_title(body, wiki_id),
                      "contentMarkdown": body})
    return {
        "questionId": str(500 + index),
        "conversationId": f"chat-sim-{index}",
        "questionType": question_type,
        "question": question["question"],
        "conversationMessages": [],
        "selectedWikis": wikis,
        "selectedSchedules": [by_id[sid] for sid in schedule_ids if sid in by_id],
    }


def score(expected: list[str], actual: list[str]) -> dict:
    """정답 포함률·순위·오답 유입."""
    expected_set = set(expected)
    hits = [i for i, value in enumerate(actual) if value in expected_set]
    return {
        "expected": expected,
        "actual": actual,
        "hit": bool(hits),
        "allHit": expected_set.issubset(set(actual)),
        "rankOfFirstHit": (hits[0] + 1) if hits else None,
        "noise": sum(1 for value in actual if value not in expected_set),
    }


def _post(api: str, path: str, key: str, body: dict) -> tuple[int, dict]:
    import httpx

    response = httpx.post(f"{api}{path}", json=body,
                          headers={"X-Internal-API-Key": key}, timeout=180.0)
    try:
        return response.status_code, response.json()
    except ValueError:
        return response.status_code, {"raw": response.text[:400]}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="챗봇 답변 API 측정 하네스")
    parser.add_argument("--plan", default=str(DEFAULT_PLAN))
    parser.add_argument("--api", default="http://127.0.0.1:8000")
    parser.add_argument("--key", default="")
    parser.add_argument("--wiki", default="",
                        help="위키 디렉터리. 비우면 plan 의 wikiDir 을 쓴다")
    parser.add_argument("--limit", type=int, default=0, help="질문 수 (0=전부)")
    parser.add_argument("--stage", choices=["1", "2", "both"], default="1")
    parser.add_argument("--dry-run", action="store_true",
                        help="호출하지 않고 조립된 요청 크기만 출력한다 (크레딧 0)")
    parser.add_argument("--out", default="")
    args = parser.parse_args(argv)

    plan = load_plan(args.plan)
    wiki_dir = Path(args.wiki) if args.wiki else ROOT / plan["wikiDir"]
    questions = plan["questions"][:args.limit] if args.limit else plan["questions"]

    rows = []
    for index, question in enumerate(questions):
        context_body = build_context_body(plan, question, wiki_dir, index=index)
        if args.dry_run:
            index_md = context_body["wikiIndexes"][0]["indexMarkdown"]
            print(f"[{index}] {question['question']}")
            print(f"    목차 {len(index_md.encode('utf-8')):,} 바이트 · "
                  f"일정 {len(context_body['scheduleSummaries'])}건 · "
                  f"기대 위키 {question['expectedWikiIds']} · "
                  f"기대 분류 {question['expectedType']}")
            continue

        status, picked = _post(args.api, "/internal/v1/answer-context-selections",
                               args.key, context_body)
        row = {"question": question["question"], "stage1Status": status,
               "stage1": picked}
        if status == 200:
            row["wikiScore"] = score(question["expectedWikiIds"],
                                     picked.get("wikiIds", []))
            row["scheduleScore"] = score(question["expectedScheduleIds"],
                                         picked.get("scheduleIds", []))
            row["typeHit"] = picked.get("questionType") == question["expectedType"]

        if args.stage in ("2", "both") and status == 200:
            answer_body = build_answer_body(
                plan, question, wiki_dir,
                question_type=picked["questionType"],
                wiki_ids=picked.get("wikiIds", []),
                schedule_ids=picked.get("scheduleIds", []), index=index)
            row["stage2Status"], row["stage2"] = _post(
                args.api, "/internal/v1/answers", args.key, answer_body)

        rows.append(row)
        print(json.dumps(row, ensure_ascii=False)[:600])

    if args.dry_run:
        print("\ndry-run — 호출하지 않았다. 크레딧 소모 0.")
        return 0

    report = {"api": args.api, "stage": args.stage, "rows": rows}
    if args.out:
        Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                  encoding="utf-8")
        print(f"\n리포트: {args.out}")
    graded = [r for r in rows if "wikiScore" in r]
    hits = sum(1 for r in graded if r["wikiScore"]["hit"])
    types = sum(1 for r in graded if r.get("typeHit"))
    print(f"\n위키 정답 포함 {hits}/{len(graded)} · 분류 정확 {types}/{len(graded)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
