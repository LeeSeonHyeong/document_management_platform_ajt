#!/usr/bin/env python3
"""문의·답변의 정본. 몇 번을 돌려도 결과가 같다 (전부 지우고 다시 넣는다).

시드에는 `[샘플]` 3건뿐이었고 `inquiry_reply` 가 0건이라 답변 달린 문의가 하나도
없었다. 관리자 화면의 문의함이 비어 있고, 사원이 묻고 관리자가 답하는 흐름을
보여줄 데이터가 없었다.

**문의는 챗봇이 답할 수 없는 것으로 짠다.** 위키에 있는 내용을 다시 묻는 문의는
"그건 챗봇에 물어보세요" 로 끝나 버려서 이 기능이 왜 있는지 설명하지 못한다.
개인 데이터(내 잔여 연차), 규정의 예외 승인(재택 주 3일), 권한 요청처럼 **사람이
판단해야 하는 것**만 담았다. 그래야 챗봇과 문의가 각자 다른 일을 한다.

`inquiry_reply.inquiry_id` 는 UNIQUE 다 — 문의 하나에 답변 하나. 그래서 DONE 인
문의에만 답변이 붙고 PENDING 에는 없다. 스크립트가 이 짝을 검사한다.

    ./tools/demo-data/seed-inquiries.py --dry-run
    ./tools/demo-data/seed-inquiries.py --apply
"""

from __future__ import annotations

import argparse
import subprocess
import sys

BONBU = "seojun.lee@ajt.com"        # 이서준 · 경영본부장 (총무·권한)
DEV_LEAD = "woojin.choi@ajt.com"    # 최우진 · 개발팀장
DESIGN_LEAD = "soyul.han@ajt.com"   # 한소율 · 디자인팀장
HR_LEAD = "doyun.kim@ajt.com"       # 김도윤 · 인사팀장

# (묻는 사람, 담당자, 제목, 본문, 우선순위, 등록일, 답변) — 답변이 None 이면 PENDING
INQUIRIES = [
    ("seongho.bae@ajt.com", HR_LEAD, "연차 잔여일수 확인 요청",
     "올해 사용한 연차와 남은 일수를 알려주실 수 있을까요? 하계휴가 권장 기간에 맞춰 계획을 세우려고 합니다.",
     "NORMAL", "2026-07-20 10:12:00",
     "2026년 회계연도 기준 부여 20일 중 6일을 사용하여 14일이 남았습니다. "
     "가산휴가는 계속근로 3년 이상부터 적용되어 올해는 해당되지 않습니다. "
     "사용 신청은 사용일 3일 전까지 결재 시스템에 올려주세요."),

    ("haneul.cho@ajt.com", HR_LEAD, "육아기 근로시간 단축 신청 절차",
     "만 7세 자녀가 있어 근로시간 단축을 신청하려 합니다. 필요한 서류와 처리 기간을 알려주세요.",
     "NORMAL", "2026-07-24 14:38:00",
     "가족관계증명서와 신청서를 인사팀에 제출하시면 됩니다. 접수 후 2영업일 이내에 승인 여부를 회신합니다. "
     "단축 시간대는 부서장과 협의해 정하며, 확정되면 근태 시스템에 반영해 드립니다."),

    ("seojun.kim@ajt.com", DEV_LEAD, "재택근무 주 3일 예외 신청",
     "다음 달 가족 간병이 필요해 한 달간 주 3일 재택을 신청드립니다. 규정상 주 2일이 한도인 것은 알고 있습니다.",
     "HIGH", "2026-07-28 09:05:00",
     "사유를 확인했습니다. 8월 한 달에 한하여 주 3일 재택을 승인합니다. "
     "집중근무시간(10~16시) 응답 유지와 스프린트 회의 대면 참석 두 가지만 지켜주세요. "
     "9월부터는 다시 주 2일 한도로 돌아갑니다."),

    ("minseo.kang@ajt.com", HR_LEAD, "건강검진 지정 병원 변경 가능한가요",
     "안내받은 검진 기관이 집에서 멀어 다른 병원에서 받고 싶습니다. 가능한지 궁금합니다.",
     "NORMAL", "2026-07-30 11:20:00",
     "제휴 기관 목록 내에서는 자유롭게 선택하실 수 있습니다. 목록 밖 기관을 원하시면 "
     "본인 부담으로 진행한 뒤 영수증을 제출해 주시면 제휴가 기준으로 정산해 드립니다."),

    ("chaewon.lim@ajt.com", HR_LEAD, "경조금 신청 시 증빙서류 원본 제출 여부",
     "청첩장 원본을 내야 하나요, 사본이나 사진도 되나요?",
     "LOW", "2026-08-03 16:45:00",
     "사본과 사진 모두 인정됩니다. 결재 시스템에 첨부해 주시면 되고 원본은 보관하지 않습니다."),

    ("jihwan.oh@ajt.com", DEV_LEAD, "개발용 노트북 교체 요청",
     "사용 중인 장비의 빌드 시간이 크게 늘어 업무에 지장이 있습니다. 교체 기준이 어떻게 되는지 궁금합니다.",
     "NORMAL", "2026-08-04 13:02:00",
     "지급 후 3년이 지난 장비는 교체 대상입니다. 사용 중인 장비가 2022년 지급분이라 대상에 해당합니다. "
     "총무팀에 신청서를 올려두었고 다음 주 중 수령 가능합니다. 기존 장비는 반납해 주세요."),

    ("jiwoo.nam@ajt.com", DESIGN_LEAD, "디자인 시스템 v2.1 적용 일정",
     "진행 중인 화면에 v2.1 토큰을 언제부터 적용해야 하는지 알려주세요.",
     "NORMAL", "2026-08-05 10:30:00",
     "8월 20일 배포 이후 새로 시작하는 화면부터 적용합니다. 이미 작업 중인 화면은 "
     "현재 스프린트를 마친 뒤 다음 스프린트에서 일괄 전환합니다."),

    ("harin.song@ajt.com", BONBU, "사내 헬스장 사물함 추가 배정 요청",
     "이번 분기 추첨에서 탈락했는데 잔여 사물함이 있으면 배정 가능한지 문의드립니다.",
     "LOW", "2026-08-05 17:55:00",
     "이번 분기 잔여분은 없습니다. 다음 분기 추첨은 9월 마지막 주에 신청받습니다. "
     "탈락자에게는 우선순위를 부여하고 있어 다음 회차에는 배정 가능성이 높습니다."),

    # --- 아직 처리 전 (PENDING)
    ("chaea.yoon@ajt.com", BONBU, "통근버스 판교 노선 정차지 추가 요청",
     "판교 노선에 정자역 정차를 추가해 주실 수 있을까요? 같은 방향 이용자가 여러 명 있습니다.",
     "HIGH", "2026-08-06 09:40:00", None),

    ("yerin.jung@ajt.com", DESIGN_LEAD, "브랜드 로고 외부 제휴사 제공 가능 여부",
     "제휴사 홍보물에 로고를 넣고 싶다는 요청을 받았습니다. 어떤 형식으로 어디까지 제공할 수 있나요?",
     "HIGH", "2026-08-06 15:12:00", None),

    ("taeho.moon@ajt.com", HR_LEAD, "사외 콘퍼런스 참가비 지원 한도 초과분 처리",
     "참가비가 지원 한도를 넘습니다. 초과분을 개인 부담으로 처리하면 나머지는 지원받을 수 있나요?",
     "NORMAL", "2026-08-07 11:08:00", None),

    ("eunwoo.cha@ajt.com", HR_LEAD, "퇴사 예정자 잔여 연차 정산",
     "퇴사일까지 사용하지 못한 연차의 정산 기준과 지급 시점을 알려주세요.",
     "HIGH", "2026-08-07 14:26:00", None),

    ("mingyu.jung@ajt.com", BONBU, "인사부 위키 열람 권한 요청",
     "온보딩 과제로 인사 규정을 확인해야 하는데 인사부 위키가 보이지 않습니다. 열람 권한을 받을 수 있을까요?",
     "NORMAL", "2026-08-08 10:02:00", None),

    ("dain.seo@ajt.com", HR_LEAD, "출장 중 심야 이동 교통비 인정 범위",
     "막차가 끊겨 택시를 이용했습니다. 실비 인정이 되는지, 사전 승인이 필요한 건인지 확인 부탁드립니다.",
     "NORMAL", "2026-08-08 18:33:00", None),
]


def lit(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def mysql(sql: str, container: str, db: str, *, read: bool = False) -> str:
    flags = "--default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD"
    if read:
        flags += " -N --batch"
    proc = subprocess.run(
        ["docker", "exec", "-i", container, "bash", "-c", f"mysql {flags} -D {db}"],
        input=sql, capture_output=True, text=True,
    )
    stderr = "\n".join(l for l in proc.stderr.splitlines() if "Using a password" not in l)
    if proc.returncode != 0 or stderr.strip():
        raise SystemExit(f"SQL 실패 (코드 {proc.returncode}): {stderr.strip() or sql[:200]}")
    return proc.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="ajt-mysql")
    parser.add_argument("--db", default="ajt_demo")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    if args.db in {"ajt", "ajt_prod"}:
        print(f"원본 DB 에는 실행하지 않는다: {args.db}", file=sys.stderr)
        return 1
    if not (args.dry_run or args.apply):
        print("--dry-run 또는 --apply 를 지정해라", file=sys.stderr)
        return 1

    rows = mysql("SELECT member_id, email, name, role FROM member;",
                 args.container, args.db, read=True)
    by_email = {}
    for line in rows.strip().splitlines():
        mid, email, name, role = line.split("\t")
        by_email[email] = (int(mid), name, role)

    problems = []
    for asker, assignee, title, _c, prio, created, reply in INQUIRIES:
        if asker not in by_email:
            problems.append(f"문의자 없음: {asker} — {title}")
        if assignee not in by_email:
            problems.append(f"담당자 없음: {assignee} — {title}")
        elif by_email[assignee][2] != "ADMIN":
            problems.append(f"담당자가 관리자가 아니다: {assignee} — {title}")
        if prio not in {"HIGH", "NORMAL", "LOW"}:
            problems.append(f"우선순위 값이 틀렸다: {prio} — {title}")
        if reply is not None and len(reply) < 20:
            problems.append(f"답변이 너무 짧다 — {title}")
    if problems:
        print("사전 검사 실패:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1

    done = sum(1 for i in INQUIRIES if i[6] is not None)
    pend = len(INQUIRIES) - done
    high = sum(1 for i in INQUIRIES if i[4] == "HIGH")
    print(f"사전 검사 통과 — 문의 {len(INQUIRIES)}건 "
          f"(답변 완료 {done} · 대기 {pend}, 긴급 {high})")

    if args.dry_run:
        for asker, assignee, title, _c, prio, created, reply in INQUIRIES:
            state = "DONE " if reply else "대기 "
            print(f"  [{state}][{prio:6}] {created[:10]} {by_email[asker][1]:4} → "
                  f"{by_email[assignee][1]:4}  {title}")
        return 0

    statements = ["DELETE FROM inquiry_reply;", "DELETE FROM inquiry;"]
    next_id = 1
    for asker, assignee, title, content, prio, created, reply in INQUIRIES:
        iid = next_id
        next_id += 1
        status = "DONE" if reply else "PENDING"
        statements.append(
            "INSERT INTO inquiry (inquiry_id, member_id, assignee_id, title, content, "
            "priority, status, attachment_refs, created_at, updated_at) VALUES ("
            f"{iid}, {by_email[asker][0]}, {by_email[assignee][0]}, {lit(title)}, "
            f"{lit(content)}, {lit(prio)}, {lit(status)}, {lit('[]')}, "
            f"{lit(created)}, {lit(created)});")
        if reply:
            # 답변은 문의 다음 영업일 안에 달린 것으로 둔다 (등록 시각 + 하루)
            statements.append(
                "INSERT INTO inquiry_reply (inquiry_id, member_id, reply, created_at, updated_at) "
                f"VALUES ({iid}, {by_email[assignee][0]}, {lit(reply)}, "
                f"DATE_ADD({lit(created)}, INTERVAL 1 DAY), "
                f"DATE_ADD({lit(created)}, INTERVAL 1 DAY));")

    mysql("START TRANSACTION;\n" + "\n".join(statements) + "\nCOMMIT;", args.container, args.db)

    # 문의 상태와 답변 유무가 어긋나지 않는지 확인한다
    bad = mysql("SELECT COUNT(*) FROM inquiry i LEFT JOIN inquiry_reply r ON r.inquiry_id=i.inquiry_id "
                "WHERE (i.status='DONE' AND r.inquiry_reply_id IS NULL) "
                "OR (i.status='PENDING' AND r.inquiry_reply_id IS NOT NULL);",
                args.container, args.db, read=True).strip()
    if bad != "0":
        print(f"상태와 답변이 어긋난 문의가 {bad}건 있다", file=sys.stderr)
        return 1
    print(f"반영 완료 — 문의 {len(INQUIRIES)}건, 답변 {done}건 (상태-답변 짝 검사 통과)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
