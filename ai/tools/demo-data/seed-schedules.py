#!/usr/bin/env python3
"""손으로 넣는 시연용 회사 일정의 정본. 몇 번을 돌려도 결과가 같다.

`--apply` 는 **먼저 지우고 다시 넣는다.** 그러지 않으면 일정 하나를 고칠 때마다
49건이 통째로 또 들어가서, 손으로 지우는 뒷정리가 따라붙는다.

**AI 가 추출한 일정은 건드리지 않는다.** `source_group_key` 가 있는 행은 원본문서
(`01-august-notice.md`)에서 뽑혀 나온 것이라 문서-일정 연결을 보여주는 시연 자산이다.
이 스크립트가 관리하는 것은 `source_group_key IS NULL` 인 행, 즉 사람이 등록한 일정뿐이다.

**작성자는 이메일로 찾는다.** member_id 를 박아 두면 명부를 다시 세울 때 엉뚱한
사람이 작성자가 된다 (`seed-members.py` 로 한 번 갈아엎었다).

**시각은 UTC 로 저장한다.** `application.yml` 이 `hibernate.jdbc.time_zone: UTC` 로
못박아 두었기 때문이다. 종일 일정은 KST 자정 경계를 옮겨 `전날 15:00 ~ 당일
14:59:59.999999` 로 적는다 — 이 변환을 빼먹으면 캘린더에서 하루 밀려 보인다.

**재택근무는 1인당 주 2일을 넘기지 않는다.** 「근무시간 및 근무형태 안내」 위키가
정한 한도다. 데이터가 규정을 어기면 챗봇 답과 캘린더가 어긋난다 — 스크립트가 검사한다.

    ./tools/demo-data/seed-schedules.py --dry-run
    ./tools/demo-data/seed-schedules.py --apply
"""

from __future__ import annotations

import argparse
import datetime as dt
import subprocess
import sys
from collections import defaultdict

# 이메일로 사람을 가리킨다 (seed-members.py 의 명부와 같은 주소)
BONBU = "seojun.lee@ajt.com"        # 이서준 · 경영본부장
DEV_LEAD = "woojin.choi@ajt.com"    # 최우진 · 개발팀장
DESIGN_LEAD = "soyul.han@ajt.com"   # 한소율 · 디자인팀장
HR_LEAD = "doyun.kim@ajt.com"       # 김도윤 · 인사팀장

DEV = ["seongho.bae@ajt.com", "jihwan.oh@ajt.com", "seojun.kim@ajt.com",
       "haeun.lee@ajt.com", "yujin.shin@ajt.com", "doyun.park@ajt.com",
       "taeho.moon@ajt.com", "seoyun.baek@ajt.com", "hajun.ryu@ajt.com",
       "mingyu.jung@ajt.com"]
DESIGN = ["jiwoo.nam@ajt.com", "chaea.yoon@ajt.com", "yerin.jung@ajt.com",
          "minseo.kang@ajt.com", "haneul.cho@ajt.com", "eunsung.ko@ajt.com"]
HR = ["harin.song@ajt.com", "jooha.hwang@ajt.com", "jiho.yoon@ajt.com",
      "chaewon.lim@ajt.com", "dain.seo@ajt.com", "soyun.jeon@ajt.com"]

DEPT_DEV, DEPT_DESIGN, DEPT_HR = 1, 3, 4


def kst(y, m, d, hh=0, mm=0) -> str:
    return (dt.datetime(y, m, d, hh, mm) - dt.timedelta(hours=9)).strftime("%Y-%m-%d %H:%M:%S.%f")


def allday(y, m, d, until=None) -> tuple[str, str]:
    ey, em, ed = until or (y, m, d)
    start = dt.datetime(y, m, d) - dt.timedelta(hours=9)
    end = dt.datetime(ey, em, ed) + dt.timedelta(days=1) - dt.timedelta(hours=9, microseconds=1)
    return start.strftime("%Y-%m-%d %H:%M:%S.%f"), end.strftime("%Y-%m-%d %H:%M:%S.%f")


def timed(y, m, d, sh, sm, eh, em) -> tuple[str, str]:
    return kst(y, m, d, sh, sm), kst(y, m, d, eh, em)


SCHEDULES: list[dict] = []


def add(title, vis, author, period, *, dept=None, location=None,
        target=None, status="APPROVED", content=None):
    SCHEDULES.append({"title": title, "vis": vis, "author": author, "dept": dept,
                      "start": period[0], "end": period[1], "location": location,
                      "target": target, "status": status, "content": content})


# ============================================================== 전사 (ALL)
add("미사용 연차 사용 촉진 안내", "ALL", HR_LEAD, allday(2026, 7, 1), target="전 직원",
    content="사용기간 종료 6개월 전 기준 서면 촉구. 잔여 연차를 확인하고 사용 시기를 등록한다.")
add("상반기 성과 리뷰 면담", "ALL", HR_LEAD, allday(2026, 7, 13, (2026, 7, 24)),
    target="전 직원", content="팀장과 1:1 면담을 진행한다. 면담 일정은 팀별로 조율한다.")
add("하계휴가 권장 기간", "ALL", HR_LEAD, allday(2026, 8, 3, (2026, 8, 14)), target="전 직원",
    content="이 기간에 연차 사용을 권장한다. 팀별로 최소 인원은 유지한다.")
add("사내 헬스장 정기 점검 휴관", "ALL", BONBU, allday(2026, 8, 17),
    location="지하 1층 헬스장", target="전 직원",
    content="설비 점검으로 하루 휴관한다. 샤워실도 함께 이용할 수 없다.")
add("8월 전사 타운홀", "ALL", BONBU, timed(2026, 8, 28, 16, 0, 17, 30),
    location="본사 5층 대강당", target="전 직원",
    content="분기 실적 공유와 질의응답. 온라인 중계도 함께 진행한다.")
add("2026년 종합건강검진 기간", "ALL", HR_LEAD, allday(2026, 9, 1, (2026, 9, 30)),
    target="전 직원",
    content="연 1회 종합건강검진. 검진 당일은 유급으로 처리하며 연차를 차감하지 않는다. "
            "배우자 검진은 본인 검진의 50퍼센트 범위에서 지원한다.")
add("통근버스 노선 개편 시행", "ALL", BONBU, allday(2026, 9, 1), target="통근버스 이용자",
    content="강남·판교·잠실 3개 노선의 정차지를 조정한다. 본사 08시 40분 도착 기준은 유지한다.")
add("창립기념일 휴무", "ALL", HR_LEAD, allday(2026, 9, 15), target="전 직원",
    content="전사 휴무. 연차를 차감하지 않는다.")
add("9월 급여 지급 (연휴로 앞당김)", "ALL", HR_LEAD, allday(2026, 9, 23), target="전 직원",
    content="정기 지급일인 24일이 추석 연휴와 겹쳐 23일에 지급한다.")
add("추석 연휴", "ALL", HR_LEAD, allday(2026, 9, 24, (2026, 9, 26)), target="전 직원")
add("4분기 OKR 수립 워크숍", "ALL", BONBU, timed(2026, 9, 29, 10, 0, 17, 0),
    location="본사 5층 대회의실", target="팀장 이상",
    content="부서별 4분기 목표를 정리해 사전 공유한다.")
add("정보보안 교육 이수 마감", "ALL", BONBU, allday(2026, 10, 30), target="전 직원",
    content="온라인 과정 이수 후 수료증을 제출한다. 미이수자는 재수강 대상이다.")
add("10월 전사 타운홀", "ALL", BONBU, timed(2026, 10, 30, 16, 0, 17, 30),
    location="본사 5층 대강당", target="전 직원", status="DRAFT", content="일정 확정 전 초안이다.")

# ============================================================ 개발부 (D1)
for day, label in [((2026, 8, 10), None), ((2026, 8, 24), None), ((2026, 9, 7), None),
                   ((2026, 9, 21), None)]:
    add("스프린트 계획 회의", "DEPARTMENT", DEV_LEAD, timed(*day, 10, 0, 12, 0),
        dept=DEPT_DEV, location="본사 3층 소회의실", target="개발부 전원",
        content="이번 스프린트 백로그를 확정한다.")
for day in [(2026, 8, 21), (2026, 9, 4), (2026, 9, 18)]:
    add("스프린트 회고", "DEPARTMENT", DEV_LEAD, timed(*day, 15, 0, 16, 30),
        dept=DEPT_DEV, location="본사 3층 소회의실", target="개발부 전원")
add("코드 프리즈", "DEPARTMENT", DEV_LEAD, allday(2026, 8, 12), dept=DEPT_DEV,
    target="개발부 전원", content="배포 전날 코드 프리즈. 긴급 수정만 반영한다.")
for day in [(2026, 8, 13), (2026, 8, 27), (2026, 9, 10)]:
    add("정기 배포", "DEPARTMENT", DEV_LEAD, timed(*day, 19, 0, 21, 0), dept=DEPT_DEV,
        target="배포 담당",
        content="블루-그린 방식으로 전환한다. 이전 버전 컨테이너는 전환 후 10분간 유지한다.")
add("기술 세미나 — 검색 색인 구조", "DEPARTMENT", DEV_LEAD, timed(2026, 8, 19, 16, 0, 17, 0),
    dept=DEPT_DEV, location="본사 3층 소회의실", target="개발부 전원")
add("온콜 당번 인수인계", "DEPARTMENT", DEV_LEAD, timed(2026, 9, 1, 9, 30, 10, 0),
    dept=DEPT_DEV, target="개발부 전원", content="9월 온콜 당번을 인계한다.")
add("신규 입사자 개발환경 세팅 지원", "DEPARTMENT", DEV_LEAD, timed(2026, 9, 1, 14, 0, 17, 0),
    dept=DEPT_DEV, location="본사 3층 소회의실", target="멘토 담당자")
add("장애 대응 훈련", "DEPARTMENT", DEV_LEAD, timed(2026, 9, 17, 14, 0, 16, 0),
    dept=DEPT_DEV, target="개발부 전원", status="DRAFT",
    content="롤백 절차를 점검한다. 일정 조율 중이다.")

# =========================================================== 디자인부 (D3)
for day in [(2026, 8, 11), (2026, 8, 18), (2026, 8, 25), (2026, 9, 1), (2026, 9, 8),
            (2026, 9, 15), (2026, 9, 22)]:
    add("주간 디자인 리뷰", "DEPARTMENT", DESIGN_LEAD, timed(*day, 14, 0, 15, 30),
        dept=DEPT_DESIGN, location="본사 3층 소회의실", target="디자인부 전원")
add("디자인 시스템 v2.1 배포", "DEPARTMENT", DESIGN_LEAD, allday(2026, 8, 20),
    dept=DEPT_DESIGN, target="디자인부 전원",
    content="컴포넌트 토큰 명명 규칙을 정리한 개정본을 배포한다.")
add("브랜드 가이드 개정 검토", "DEPARTMENT", DESIGN_LEAD, timed(2026, 9, 3, 11, 0, 12, 0),
    dept=DEPT_DESIGN, location="본사 3층 소회의실", target="디자인부 전원",
    content="로고 여백 규정과 색상 대체 팔레트를 다시 본다.")
add("접근성 점검 스프린트", "DEPARTMENT", DESIGN_LEAD, allday(2026, 9, 9, (2026, 9, 11)),
    dept=DEPT_DESIGN, target="디자인부 전원",
    content="명도 대비와 키보드 이동 경로를 화면 단위로 점검한다.")
add("하반기 디자인 트렌드 공유회", "DEPARTMENT", DESIGN_LEAD, timed(2026, 9, 23, 15, 0, 16, 30),
    dept=DEPT_DESIGN, location="본사 3층 소회의실", target="디자인부 전원", status="DRAFT")

# ============================================================= 인사부 (D4)
add("하반기 신입 채용 서류 마감", "DEPARTMENT", HR_LEAD, allday(2026, 8, 14),
    dept=DEPT_HR, target="인사부 전원", content="접수분을 분류해 1차 검토 대상을 추린다.")
add("퇴사자 면담", "DEPARTMENT", HR_LEAD, timed(2026, 8, 18, 14, 0, 15, 0),
    dept=DEPT_HR, location="본사 3층 상담실", target="인사부 담당자",
    content="퇴사 절차 안내와 자산 반납 확인.")
add("하반기 신입 1차 면접", "DEPARTMENT", HR_LEAD, allday(2026, 8, 20, (2026, 8, 21)),
    dept=DEPT_HR, location="본사 5층 대회의실", target="인사부 전원, 실무 면접관")
add("하반기 신입 2차 임원 면접", "DEPARTMENT", HR_LEAD, timed(2026, 8, 27, 13, 0, 18, 0),
    dept=DEPT_HR, location="본사 5층 대회의실", target="인사부 전원, 임원")
add("가입 승인 요청 일괄 처리", "DEPARTMENT", HR_LEAD, timed(2026, 8, 31, 10, 0, 11, 0),
    dept=DEPT_HR, target="인사부 담당자", content="대기 중인 사내 계정 가입 요청을 검토한다.")
add("신입 온보딩 프로그램", "DEPARTMENT", HR_LEAD, allday(2026, 9, 1, (2026, 9, 4)),
    dept=DEPT_HR, location="본사 5층 대회의실", target="신규 입사자, 인사부",
    content="4일 과정. 사규·복지제도·정보보안 교육을 포함한다.")
add("건강검진 예약 안내 발송", "DEPARTMENT", HR_LEAD, timed(2026, 9, 2, 10, 0, 11, 0),
    dept=DEPT_HR, target="인사부 담당자")
add("인사평가 시스템 오픈", "DEPARTMENT", HR_LEAD, allday(2026, 9, 15),
    dept=DEPT_HR, target="인사부 전원", content="하반기 평가 문항을 등록하고 대상자를 확정한다.")
add("경력 개발 상담 주간", "DEPARTMENT", HR_LEAD, allday(2026, 9, 21, (2026, 9, 25)),
    dept=DEPT_HR, target="인사부 전원", status="DRAFT",
    content="승진 대상자 상담 일정을 조율 중이다.")

# ========================================================== 개인 (PERSONAL)
# 재택은 「근무시간 및 근무형태 안내」의 주 2일 한도를 지킨다.
REMOTE = [
    ("seojun.kim@ajt.com",  [(8, 11), (8, 13), (8, 18), (8, 20), (9, 1), (9, 3)]),
    ("haeun.lee@ajt.com",   [(8, 12), (8, 19), (9, 2), (9, 10)]),
    ("yujin.shin@ajt.com",  [(8, 11), (8, 25), (8, 27), (9, 8)]),
    ("taeho.moon@ajt.com",  [(8, 13), (8, 20), (9, 3)]),
    ("hajun.ryu@ajt.com",   [(8, 18), (9, 1), (9, 15)]),
    ("jihwan.oh@ajt.com",   [(8, 25), (9, 9)]),
    ("yerin.jung@ajt.com",  [(8, 12), (8, 26), (9, 9)]),
    ("jiwoo.nam@ajt.com",   [(8, 18), (9, 2)]),
    ("chaea.yoon@ajt.com",  [(8, 19), (9, 16)]),
    ("jiho.yoon@ajt.com",   [(8, 26), (9, 16)]),
    ("harin.song@ajt.com",  [(8, 11), (9, 8)]),
    ("jooha.hwang@ajt.com", [(9, 10)]),
]
for who, days in REMOTE:
    for mm, dd in days:
        add("재택근무", "PERSONAL", who, allday(2026, mm, dd), target="본인")

LEAVE = [
    ("seongho.bae@ajt.com", (8, 14), None, "연차"),
    ("doyun.park@ajt.com",  (8, 17), (2026, 8, 19), "연차"),
    ("seoyun.baek@ajt.com", (8, 10), (2026, 8, 12), "연차"),
    ("mingyu.jung@ajt.com", (8, 21), None, "연차"),
    ("minseo.kang@ajt.com", (8, 13), (2026, 8, 14), "연차"),
    ("haneul.cho@ajt.com",  (8, 24), None, "연차"),
    ("eunsung.ko@ajt.com",  (9, 7), (2026, 9, 8), "연차"),
    ("chaewon.lim@ajt.com", (8, 31), None, "연차"),
    ("dain.seo@ajt.com",    (9, 14), None, "연차"),
    ("soyun.jeon@ajt.com",  (8, 28), None, "연차"),
    ("jiwoo.nam@ajt.com",   (9, 22), None, "연차"),
    ("seojun.kim@ajt.com",  (9, 28), (2026, 9, 29), "연차"),
]
for who, start, until, label in LEAVE:
    add(label, "PERSONAL", who, allday(2026, start[0], start[1], until), target="본인")

add("반차 (오후)", "PERSONAL", "haeun.lee@ajt.com", timed(2026, 8, 14, 13, 0, 18, 0),
    target="본인")
add("반차 (오전)", "PERSONAL", "yerin.jung@ajt.com", timed(2026, 9, 2, 9, 0, 13, 0),
    target="본인")
add("반차 (오후)", "PERSONAL", "taeho.moon@ajt.com", timed(2026, 9, 11, 13, 0, 18, 0),
    target="본인")

for who, mm, dd in [("minseo.kang@ajt.com", 9, 8), ("seongho.bae@ajt.com", 9, 16),
                    ("harin.song@ajt.com", 9, 17), ("doyun.park@ajt.com", 9, 30)]:
    add("종합건강검진", "PERSONAL", who, timed(2026, mm, dd, 9, 0, 12, 0), target="본인",
        content="검진 당일은 유급으로 처리되며 연차를 차감하지 않는다.")

add("사외 교육 수강", "PERSONAL", "chaewon.lim@ajt.com", allday(2026, 8, 25, (2026, 8, 26)),
    target="본인", content="교육비 지원 신청 완료.")
add("사외 콘퍼런스 참석", "PERSONAL", "jihwan.oh@ajt.com", allday(2026, 9, 17, (2026, 9, 18)),
    target="본인", content="도서·교육 지원 예산으로 참가비를 처리한다.")
add("육아기 근로시간 단축 (오후 1시간)", "PERSONAL", "haneul.cho@ajt.com",
    timed(2026, 9, 1, 17, 0, 18, 0), target="본인",
    content="만 8세 이하 자녀 대상. 단축 시간은 근무시간 운영지침을 따른다.")


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


def to_kst(stamp: str) -> dt.datetime:
    return dt.datetime.strptime(stamp, "%Y-%m-%d %H:%M:%S.%f") + dt.timedelta(hours=9)


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

    rows = mysql("SELECT member_id, email, name FROM member;", args.container, args.db, read=True)
    by_email = {}
    for line in rows.strip().splitlines():
        mid, email, name = line.split("\t")
        by_email[email] = (int(mid), name)

    problems = []
    # 주말·재택 한도·작성자 검사
    remote_weeks: dict[tuple[str, tuple], int] = defaultdict(int)
    for s in SCHEDULES:
        if s["author"] not in by_email:
            problems.append(f"작성자 없음: {s['author']} — {s['title']}")
        if s["vis"] == "DEPARTMENT" and s["dept"] is None:
            problems.append(f"부서 일정에 부서가 없다 — {s['title']}")
        if s["start"] >= s["end"]:
            problems.append(f"시작이 종료보다 늦다 — {s['title']}")
        start = to_kst(s["start"])
        if start.weekday() >= 5 and "연휴" not in s["title"]:
            problems.append(f"주말에 걸렸다 — {s['title']} {start:%Y-%m-%d(%a)}")
        if s["title"] == "재택근무":
            remote_weeks[(s["author"], start.isocalendar()[:2])] += 1
    for (who, week), n in remote_weeks.items():
        if n > 2:
            problems.append(f"재택 주 2일 한도 초과 — {who} {week[0]}년 {week[1]}주차 {n}일")

    if problems:
        print("사전 검사 실패:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1

    counts: dict[str, int] = defaultdict(int)
    for s in SCHEDULES:
        counts[s["vis"]] += 1
    drafts = sum(1 for s in SCHEDULES if s["status"] == "DRAFT")
    people = len({s["author"] for s in SCHEDULES if s["vis"] == "PERSONAL"})
    print(f"사전 검사 통과 — {len(SCHEDULES)}건 "
          f"(전사 {counts['ALL']} · 부서 {counts['DEPARTMENT']} · 개인 {counts['PERSONAL']}, "
          f"검수 대기 {drafts}, 개인 일정을 가진 사람 {people}명)")

    if args.dry_run:
        for s in sorted(SCHEDULES, key=lambda x: x["start"]):
            who = by_email.get(s["author"], (0, "?"))[1]
            print(f"  [{s['vis']:10}] {to_kst(s['start']):%Y-%m-%d(%a)} {who:4} "
                  f"{s['title']} ({s['status']})")
        return 0

    # 손으로 넣은 일정만 지운다. AI 추출본(source_group_key 있음)은 남긴다.
    # 챗봇 답변 출처가 이 일정을 가리키고 있으면 함께 지운다. 조용히 지우면 「출처가
    # 왜 사라졌지」가 되므로 몇 건을 지웠고 무엇을 다시 돌려야 하는지 말한다.
    doomed = mysql("SELECT schedule_id FROM schedule WHERE source_group_key IS NULL;",
                   args.container, args.db, read=True).split()
    held = "0"
    if doomed:
        held = mysql("SELECT COUNT(*) FROM answer_source WHERE schedule_id IN "
                     f"({','.join(doomed)});", args.container, args.db, read=True).strip()

    statements = []
    if held != "0":
        print(f"!! 챗봇 답변 출처 {held}건이 이 일정을 가리키고 있어 함께 지운다.")
        print("   일정 id 가 새로 매겨지므로 끝나면 반드시 다시 돌려라:")
        print("   ./tools/demo-data/seed-chat-history.py --apply")
        statements.append(
            "DELETE FROM answer_source WHERE schedule_id IN "
            "(SELECT schedule_id FROM schedule WHERE source_group_key IS NULL);")
    statements += [
        "DELETE FROM schedule_department WHERE schedule_id IN "
        "(SELECT schedule_id FROM schedule WHERE source_group_key IS NULL);",
        "DELETE FROM schedule WHERE source_group_key IS NULL;",
    ]
    next_id = int(mysql("SELECT COALESCE(MAX(schedule_id),0)+1 FROM schedule;",
                        args.container, args.db, read=True).strip())
    for s in SCHEDULES:
        sid = next_id
        next_id += 1
        author = by_email[s["author"]][0]
        statements.append(
            "INSERT INTO schedule (schedule_id, author_id, title, content, target_text, "
            "location, visibility_type, start_at, end_at, status) VALUES ("
            f"{sid}, {author}, {lit(s['title'])}, {lit(s['content'])}, {lit(s['target'])}, "
            f"{lit(s['location'])}, {lit(s['vis'])}, {lit(s['start'])}, {lit(s['end'])}, "
            f"{lit(s['status'])});")
        if s["vis"] == "DEPARTMENT":
            statements.append("INSERT INTO schedule_department (schedule_id, department_id) "
                              f"VALUES ({sid}, {s['dept']});")

    mysql("START TRANSACTION;\n" + "\n".join(statements) + "\nCOMMIT;", args.container, args.db)
    kept = mysql("SELECT COUNT(*) FROM schedule WHERE source_group_key IS NOT NULL;",
                 args.container, args.db, read=True).strip()
    print(f"반영 완료 — 손으로 넣은 일정 {len(SCHEDULES)}건, AI 추출 일정 {kept}건 보존")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
