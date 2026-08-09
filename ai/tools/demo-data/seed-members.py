#!/usr/bin/env python3
"""사원 명부를 실제 회사에 가깝게 다시 세운다.

시드 계정은 `관리자`·`홍길동`·`박디자인` 처럼 역할을 이름에 박아 둔 것이었고,
이메일도 `design.admin@ajt.com` 이라 화면에 그대로 드러났다. 16명 전원의
`created_at` 이 같은 초였고, 가입 승인 대기(PENDING)와 비활성(INACTIVE)이
0건이라 관리자 화면의 「가입 승인 요청」과 통계 카드 「비활성」이 비어 있었다.

**위키에 이미 있는 인물을 계정으로 세운다.** 회의록 위키에 본부장 이서준·개발팀장
최우진·디자인팀장 한소율·인사팀장 김도윤이 의결자로 등장하는데 명부에는 없었다.
그 넷을 각 부서의 관리자 계정에 앉히면 "위키에서 의결한 사람"과 "시스템 계정"이
같은 사람이 된다.

**최고관리자는 이메일로 식별한다 — 기동 설정을 함께 바꿔야 한다.** 백엔드
`SuperAdminChecker` 는 `ajt.super-admin.email` 설정값과 이메일이 일치하는 계정만
최고관리자로 본다 (S15P11B106-146. 예전의 「부서장이 아닌 관리자」 규칙이 아니다).
기본값이 `superadmin@ajt.com` 이라, 이 명부처럼 이메일을 이름 기반으로 바꾸면
**최고관리자가 아무도 없는 상태가 된다** — 위키 수정 채팅과 가입 승인 메뉴가 통째로
사라진다. 2026-08-09 에 실제로 그렇게 만들었다. 기동 시 아래를 함께 준다:

    SPRING_APPLICATION_JSON='{"ajt": {"super-admin": {"email": "seojun.lee@ajt.com"}}, ...}'

**비밀번호는 바꾸지 않는다.** 기존 계정은 해시를 그대로 두고, 새로 넣는 계정은
같은 역할의 기존 해시를 복사한다 — 관리자 계정은 기존 관리자 비밀번호로, 사원
계정은 기존 사원 비밀번호로 로그인된다. 시연 중 아무 사원으로나 갈아타 권한
차이를 보여줄 수 있다.

    ./tools/demo-data/seed-members.py --dry-run
    ./tools/demo-data/seed-members.py --apply
"""

from __future__ import annotations

import argparse
import subprocess
import sys

DEPT_DEV, DEPT_DESIGN, DEPT_HR, DEPT_NONE, DEPT_EXEC = 1, 3, 4, 5, 6

# (member_id 또는 None, 부서, 이름, 이메일 로컬부, 역할, 입사일, signup, account)
# member_id 가 있으면 기존 행을 고치고, None 이면 새로 넣는다. 기존 id 는 일정·위키·
# 문서가 참조하므로 지우지 않는다.
ROSTER = [
    # --- 경영 (최고관리자 스코프)
    (9,    DEPT_EXEC,   "이서준", "seojun.lee",    "ADMIN",    "2017-01-02", "APPROVED", "ACTIVE"),

    # --- 개발부
    (1,    DEPT_DEV,    "최우진", "woojin.choi",   "ADMIN",    "2019-03-04", "APPROVED", "ACTIVE"),
    (2,    DEPT_DEV,    "배성호", "seongho.bae",   "EMPLOYEE", "2020-09-01", "APPROVED", "ACTIVE"),
    (None, DEPT_DEV,    "오지환", "jihwan.oh",     "EMPLOYEE", "2021-06-01", "APPROVED", "ACTIVE"),
    (14,   DEPT_DEV,    "김서준", "seojun.kim",    "EMPLOYEE", "2022-01-03", "APPROVED", "ACTIVE"),
    (15,   DEPT_DEV,    "이하은", "haeun.lee",     "EMPLOYEE", "2023-03-02", "APPROVED", "ACTIVE"),
    (None, DEPT_DEV,    "신유진", "yujin.shin",    "EMPLOYEE", "2023-09-01", "APPROVED", "ACTIVE"),
    (16,   DEPT_DEV,    "박도윤", "doyun.park",    "EMPLOYEE", "2024-07-01", "APPROVED", "ACTIVE"),
    (None, DEPT_DEV,    "문태호", "taeho.moon",    "EMPLOYEE", "2025-01-02", "APPROVED", "ACTIVE"),
    (None, DEPT_DEV,    "백서윤", "seoyun.baek",   "EMPLOYEE", "2025-08-18", "APPROVED", "ACTIVE"),
    (None, DEPT_DEV,    "류하준", "hajun.ryu",     "EMPLOYEE", "2026-03-02", "APPROVED", "ACTIVE"),
    (None, DEPT_DEV,    "정민규", "mingyu.jung",   "EMPLOYEE", "2026-06-01", "APPROVED", "ACTIVE"),

    # --- 디자인부
    (5,    DEPT_DESIGN, "한소율", "soyul.han",     "ADMIN",    "2020-01-02", "APPROVED", "ACTIVE"),
    (6,    DEPT_DESIGN, "남지우", "jiwoo.nam",     "EMPLOYEE", "2021-11-01", "APPROVED", "ACTIVE"),
    (None, DEPT_DESIGN, "윤채아", "chaea.yoon",    "EMPLOYEE", "2022-08-16", "APPROVED", "ACTIVE"),
    (17,   DEPT_DESIGN, "정예린", "yerin.jung",    "EMPLOYEE", "2023-05-02", "APPROVED", "ACTIVE"),
    (18,   DEPT_DESIGN, "강민서", "minseo.kang",   "EMPLOYEE", "2024-02-01", "APPROVED", "ACTIVE"),
    (19,   DEPT_DESIGN, "조하늘", "haneul.cho",    "EMPLOYEE", "2025-04-01", "APPROVED", "ACTIVE"),
    (None, DEPT_DESIGN, "고은성", "eunsung.ko",    "EMPLOYEE", "2026-01-05", "APPROVED", "ACTIVE"),

    # --- 인사부
    (7,    DEPT_HR,     "김도윤", "doyun.kim",     "ADMIN",    "2018-05-02", "APPROVED", "ACTIVE"),
    (8,    DEPT_HR,     "송하린", "harin.song",    "EMPLOYEE", "2021-03-02", "APPROVED", "ACTIVE"),
    (None, DEPT_HR,     "황주하", "jooha.hwang",   "EMPLOYEE", "2022-04-01", "APPROVED", "ACTIVE"),
    (20,   DEPT_HR,     "윤지호", "jiho.yoon",     "EMPLOYEE", "2023-01-03", "APPROVED", "ACTIVE"),
    (21,   DEPT_HR,     "임채원", "chaewon.lim",   "EMPLOYEE", "2024-09-02", "APPROVED", "ACTIVE"),
    (22,   DEPT_HR,     "서다인", "dain.seo",      "EMPLOYEE", "2025-06-02", "APPROVED", "ACTIVE"),
    (None, DEPT_HR,     "전소윤", "soyun.jeon",    "EMPLOYEE", "2026-02-02", "APPROVED", "ACTIVE"),

    # --- 퇴사자. 「퇴사 절차 안내」 위키가 실제로 적용된 흔적이다
    (None, DEPT_DEV,    "차은우", "eunwoo.cha",    "EMPLOYEE", "2020-05-04", "APPROVED", "INACTIVE"),
    (None, DEPT_DESIGN, "민가온", "gaon.min",      "EMPLOYEE", "2022-10-04", "APPROVED", "INACTIVE"),

    # --- 가입 승인 대기. 부서 배정 전이라 「미지정」이고, 사번은 승인 뒤에 부여하므로 없다
    (None, DEPT_NONE,   "안도현", "dohyun.ahn",    "EMPLOYEE", "2026-08-05", "PENDING",  "ACTIVE"),
    (None, DEPT_NONE,   "하수빈", "subin.ha",      "EMPLOYEE", "2026-08-07", "PENDING",  "ACTIVE"),
    (None, DEPT_NONE,   "권시우", "siwoo.kwon",    "EMPLOYEE", "2026-08-08", "PENDING",  "ACTIVE"),

    # --- 가입 거절. 사내 메일이 아닌 주소로 신청해 반려된 건이다
    (None, DEPT_NONE,   "노태윤", "taeyoon.noh@gmail.com", "EMPLOYEE", "2026-07-29", "REJECTED", "ACTIVE"),
]

# 사번을 부여하지 않는 상태 — 승인 전이라 아직 사원이 아니다
NO_EMPLOYEE_NO = {"PENDING", "REJECTED"}


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


def employee_no(hire_date: str, seq: int) -> str:
    return f"AJT-{hire_date[:4]}-{seq:04d}"


def address(local: str) -> str:
    """사내 계정은 로컬부만 적는다. 사외 주소는 통째로 적혀 온다 (가입 반려 건)."""
    return local if "@" in local else f"{local}@ajt.com"


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

    # 사번은 입사연도별 순번으로 다시 매긴다 — 기존 값은 관리자가 0002, 사원이 0001 로
    # 뒤섞여 있었다. 입사일 순으로 정렬해야 연도별 순번이 입사 순서와 맞는다.
    ordered = sorted(ROSTER, key=lambda r: (r[5], r[2]))
    per_year: dict[str, int] = {}
    numbered = []
    for row in ordered:
        if row[6] in NO_EMPLOYEE_NO:
            numbered.append((*row, None))
            continue
        year = row[5][:4]
        per_year[year] = per_year.get(year, 0) + 1
        numbered.append((*row, employee_no(row[5], per_year[year])))

    # 사전 검사
    problems = []
    emails = [address(r[3]) for r in numbered]
    if len(set(emails)) != len(emails):
        problems.append("이메일이 중복된다")
    nos = [r[-1] for r in numbered if r[-1] is not None]
    if len(set(nos)) != len(nos):
        problems.append("사번이 중복된다")
    depts = set(mysql("SELECT department_id FROM department;", args.container, args.db, read=True).split())
    for r in numbered:
        if str(r[1]) not in depts:
            problems.append(f"부서 {r[1]} 없음 — {r[2]}")
    existing = set(mysql("SELECT member_id FROM member;", args.container, args.db, read=True).split())
    for r in numbered:
        if r[0] is not None and str(r[0]) not in existing:
            problems.append(f"고치려는 계정 {r[0]} 이 없다 — {r[2]}")
    # 기존 계정 중 명부에 없는 것이 있으면 알린다 (조용히 남겨두지 않는다)
    listed = {str(r[0]) for r in numbered if r[0] is not None}
    orphans = existing - listed
    if orphans:
        problems.append(f"명부에 없는 기존 계정이 남는다: {sorted(orphans)}")
    if problems:
        print("사전 검사 실패:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1

    updates = sum(1 for r in numbered if r[0] is not None)
    inserts = len(numbered) - updates
    pend = sum(1 for r in numbered if r[6] == "PENDING")
    rej = sum(1 for r in numbered if r[6] == "REJECTED")
    inact = sum(1 for r in numbered if r[7] == "INACTIVE")
    print(f"사전 검사 통과 — 총 {len(numbered)}명 (기존 {updates}건 수정 · 신규 {inserts}건)")
    print(f"  가입 승인 대기 {pend} · 가입 거절 {rej} · 비활성(퇴사) {inact}")

    if args.dry_run:
        for r in numbered:
            mark = f"수정 {r[0]:>2}" if r[0] is not None else "신규   "
            print(f"  [{mark}] {(r[-1] or '사번없음   '):13}  {r[2]:4} {address(r[3]):24} "
                  f"{r[4]:8} 부서{r[1]} {r[5]} {r[6]}/{r[7]}")
        return 0

    # 역할별 기존 비밀번호 해시를 그대로 쓴다 (새 계정도 같은 비밀번호로 로그인된다)
    admin_hash = mysql("SELECT password_hash FROM member WHERE member_id=1;",
                       args.container, args.db, read=True).strip()
    emp_hash = mysql("SELECT password_hash FROM member WHERE member_id=2;",
                     args.container, args.db, read=True).strip()
    if not admin_hash or not emp_hash:
        print("기준 비밀번호 해시를 읽지 못했다", file=sys.stderr)
        return 1

    next_id = int(mysql("SELECT COALESCE(MAX(member_id),0)+1 FROM member;",
                        args.container, args.db, read=True).strip())

    # 사번·이메일은 UNIQUE 다. 새 값으로 한 줄씩 바꾸면 아직 안 옮긴 행과 충돌할 수 있어
    # 기존 행의 값을 먼저 임시값으로 비운 뒤 채운다.
    statements = ["SET @@session.foreign_key_checks = 1;"]
    for r in numbered:
        if r[0] is not None:
            statements.append(
                f"UPDATE member SET email=CONCAT('tmp-', member_id, '@invalid'), "
                f"employee_no=CONCAT('TMP-', member_id) WHERE member_id={r[0]};")

    for r in numbered:
        mid, dept, name, local, role, hire, signup, account, no = r
        email = address(local)
        created = f"{hire} 09:00:00.000000"
        if mid is not None:
            statements.append(
                f"UPDATE member SET department_id={dept}, email={lit(email)}, name={lit(name)}, "
                f"employee_no={lit(no)}, role={lit(role)}, signup_status={lit(signup)}, "
                f"account_status={lit(account)}, created_at={lit(created)} WHERE member_id={mid};")
        else:
            pw = admin_hash if role == "ADMIN" else emp_hash
            statements.append(
                "INSERT INTO member (member_id, department_id, email, name, password_hash, "
                "employee_no, role, signup_status, account_status, created_at) VALUES ("
                f"{next_id}, {dept}, {lit(email)}, {lit(name)}, {lit(pw)}, {lit(no)}, "
                f"{lit(role)}, {lit(signup)}, {lit(account)}, {lit(created)});")
            next_id += 1

    # 부서장은 각 부서의 ADMIN 으로 맞춘다
    for dept in (DEPT_DEV, DEPT_DESIGN, DEPT_HR):
        admin = next(r for r in numbered if r[1] == dept and r[4] == "ADMIN")
        if admin[0] is not None:
            statements.append(f"UPDATE department SET manager_id={admin[0]} "
                              f"WHERE department_id={dept};")

    mysql("START TRANSACTION;\n" + "\n".join(statements) + "\nCOMMIT;", args.container, args.db)
    print(f"반영 완료 — 계정 {len(numbered)}명")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
