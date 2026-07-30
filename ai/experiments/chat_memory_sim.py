"""멀티턴 기억 검증 — `chatHistory`/`conversationMessages`를 그냥 다 이어붙이는 방식이
실제로 맥락 참조("그거", "방금 그건")를 푸는지 확인한다.

`chat_sim.py`와 달리 `conversationMessages`를 매 턴 빈 배열로 두지 않는다 — 이전 턴의
질문·답변을 실제로 누적해 다음 턴에 실어 보낸다. 비용·토큰 최적화(sliding window·요약)는
이게 애초에 작동한다는 것을 확인한 다음에나 의미가 있다.

턴은 `chat_questions.json`의 5·6번(스톡옵션 베스팅 → 퇴사 시 행사기한)을 이어 쓴다. 두
질문이 실제로 같은 주제라 대명사로 이어붙이면 자연스러운 후속 질문이 된다.

사용:
    # 크레딧 0 — 조립된 요청만 확인
    uv run python experiments/chat_memory_sim.py --dry-run

    # 서버를 띄운 뒤 (기본 런타임 claude-code, 구독 과금)
    uv run python -m wiki_api.serve --internal-api-key k --port 8000
    uv run python experiments/chat_memory_sim.py --api http://127.0.0.1:8000 --key k
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WIKI_DIR = ROOT / "2026-07-27-opus46-12docs" / "data" / "wiki" / "ALL"

# 5번→6번 질문을 대명사로 이어 재구성. 원문은 chat_questions.json 참고.
TURNS = [
    "스톡옵션 베스팅 기간이 어떻게 돼?",
    "그럼 퇴사하면 그거 언제까지 행사할 수 있어?",
    "행사 안 하면 어떻게 되는데?",
]

# 5·6번 질문의 정답 위키. 1턴은 이것만, 2·3턴은 대명사가 안 풀리면 이 id가 안 나온다 —
# "맥락을 못 잡았다"의 가장 직접적인 신호.
STOCK_OPTION_WIKI_ID = "bfd285bfd962"


def _index_markdown() -> str:
    return (WIKI_DIR / "index.md").read_text(encoding="utf-8")


def _page_markdown(wiki_id: str) -> str:
    return (WIKI_DIR / "pages" / f"{wiki_id}.md").read_text(encoding="utf-8")


def _page_title(markdown: str, fallback: str) -> str:
    for line in markdown.split("\n")[:20]:
        stripped = line.strip()
        if stripped.startswith("title:"):
            return stripped.removeprefix("title:").strip().strip('"')
        if stripped.startswith("# "):
            return stripped.removeprefix("# ").strip()
    return fallback


def _post(api: str, path: str, key: str, body: dict) -> tuple[int, dict]:
    import httpx

    response = httpx.post(f"{api}{path}", json=body,
                          headers={"X-Internal-API-Key": key}, timeout=180.0)
    try:
        return response.status_code, response.json()
    except ValueError:
        return response.status_code, {"raw": response.text[:400]}


def load_seed_history(path: str, upto: int) -> list[dict]:
    """이전 리포트에서 턴 0..upto-1의 실제 질문·답변을 히스토리로 재구성한다.

    재호출 없이 재사용한다 — 앞 턴을 다시 물어보면 같은 질문에 크레딧이 두 번 나간다.
    """
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    history: list[dict] = []
    for row in rows[:upto]:
        history.append({"role": "user", "content": row["question"]})
        answer = (row.get("stage2") or {}).get("answer", "")
        if answer:
            history.append({"role": "assistant", "content": answer})
    return history


def run_turn(api: str, key: str, question: str, history: list[dict],
            index: int, *, dry_run: bool) -> dict:
    context_body = {
        "questionId": str(700 + index),
        "conversationId": "chat-memory-sim",
        "question": question,
        "conversationMessages": history,
        "wikiIndexes": [{"scopeKey": "ALL", "indexMarkdown": _index_markdown()}],
        "scheduleSummaries": [],
    }
    if dry_run:
        print(f"[턴 {index + 1}] {question}")
        print(f"    히스토리 {len(history)}턴 · "
              f"직전까지 누적 텍스트 {sum(len(m['content']) for m in history):,}자")
        return {"question": question, "dryRun": True}

    status1, picked = _post(api, "/internal/v1/answer-context-selections", key,
                            context_body)
    row: dict = {"question": question, "stage1Status": status1, "stage1": picked}
    if status1 != 200:
        return row

    wiki_ids = picked.get("wikiIds", [])
    wikis = [{"wikiId": wid, "title": _page_title(_page_markdown(wid), wid),
             "contentMarkdown": _page_markdown(wid)} for wid in wiki_ids]
    answer_body = {
        "questionId": str(700 + index),
        "conversationId": "chat-memory-sim",
        "questionType": picked.get("questionType", "wiki"),
        "question": question,
        "conversationMessages": history,
        "selectedWikis": wikis,
        "selectedSchedules": [],
    }
    status2, answered = _post(api, "/internal/v1/answers", key, answer_body)
    row["stage2Status"] = status2
    row["stage2"] = answered
    row["historySent"] = len(history)
    row["keptStockOptionContext"] = STOCK_OPTION_WIKI_ID in wiki_ids
    return row


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="챗봇 멀티턴 기억 검증")
    parser.add_argument("--api", default="http://127.0.0.1:8000")
    parser.add_argument("--key", default="")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--out", default="")
    parser.add_argument("--window", type=int, default=-1,
                        help="실제로 보낼 히스토리 메시지 최대 개수. -1=무제한(잘라내지 "
                             "않음). 0이면 히스토리를 아예 안 보낸다 — sliding window가 "
                             "전부 밀려난 극단을 흉내낸다")
    parser.add_argument("--seed", default="",
                        help="이전 리포트 json. --start 이전 턴은 재호출 없이 그 실제 "
                             "질문·답변을 히스토리로 재사용한다 (크레딧 절약)")
    parser.add_argument("--start", type=int, default=0,
                        help="TURNS의 몇 번째부터 실제로 호출할지 (0-based)")
    args = parser.parse_args(argv)

    full_history: list[dict] = (load_seed_history(args.seed, args.start)
                                if args.seed else [])
    rows = []
    for index, question in enumerate(TURNS):
        if index < args.start:
            continue
        # `lst[-0:]`는 "마지막 0개"가 아니라 전체다 — window=0을 `[-window:]`로 자르면
        # 파이썬의 음수 0 함정에 걸려 아무것도 안 잘린다.
        if args.window < 0:
            sent_history = full_history
        else:
            cut = max(0, len(full_history) - args.window)
            sent_history = full_history[cut:]
        row = run_turn(args.api, args.key, question, sent_history, index,
                       dry_run=args.dry_run)
        rows.append(row)
        full_history.append({"role": "user", "content": question})
        if args.dry_run:
            # 실제 답변 길이를 모르니 자리표시자로 누적 크기 추세만 보여준다.
            full_history.append({"role": "assistant",
                                 "content": "(dry-run — 실제 답변 아님, 자리표시자)"})
            continue

        print(json.dumps(row, ensure_ascii=False)[:800])
        answer_text = (row.get("stage2") or {}).get("answer", "")
        if answer_text:
            full_history.append({"role": "assistant", "content": answer_text})

    if args.dry_run:
        print("\ndry-run — 호출하지 않았다. 크레딧 소모 0.")
        return 0

    if args.out:
        Path(args.out).write_text(json.dumps(rows, ensure_ascii=False, indent=2),
                                  encoding="utf-8")
        print(f"\n리포트: {args.out}")

    kept = sum(1 for r in rows[1:] if r.get("keptStockOptionContext"))
    print(f"\n2·3턴 중 맥락 유지(정답 위키 재선택) {kept}/{len(rows) - 1}"
          f" — 사람이 답변 본문도 같이 읽고 판단할 것")
    return 0


if __name__ == "__main__":
    sys.exit(main())
