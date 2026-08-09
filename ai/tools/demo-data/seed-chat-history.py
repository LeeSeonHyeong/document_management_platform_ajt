#!/usr/bin/env python3
"""챗봇 질문·답변·출처의 정본. 몇 번을 돌려도 결과가 같다 (전부 지우고 다시 넣는다).

QA 때 쌓인 이력이 **지금 위키와 다른 말을 하고 있었다.** 도서 한도를 `$50`, 교육
예산을 `$1,000`, 북클럽을 `BookHog`, 법인카드를 `Brex` 라고 답했고 「독일 근무자」가
등장했다 — 위키에서 지운 PostHog 잔재가 챗봇 이력에만 살아 있었다. 경조금은
50만 원으로, 주식 옵션 행사 기간은 10년으로 남아 있어 각각 위키의 100만 원·8년과
어긋났다. 시연 중 이력을 열면 위키와 다른 값이 보인다.

**답변의 수치는 인용하는 위키에서 그대로 가져온다.** 이 파일을 고칠 때에도 위키를
먼저 보고 맞춘다. 여기서 지어내면 같은 사고가 반복된다.

**출처는 답변이 실제로 근거로 삼은 위키·일정만 적는다.** `answer_source` 가 비어 있으면
화면에 근거 없는 답으로 보이고, 반대로 안 읽은 것을 적으면 시연에서 눌렀을 때
엉뚱한 문서가 열린다.

**일정은 제목으로 찾는다.** `seed-schedules.py` 가 돌 때마다 schedule_id 가 새로
매겨지므로 번호를 박아 두면 다른 일정을 가리킨다. 그래서 **일정 시더를 먼저 돌리고
이 시더를 돌린다.**

    ./tools/demo-data/seed-schedules.py --apply
    ./tools/demo-data/seed-chat-history.py --apply
"""

from __future__ import annotations

import argparse
import subprocess
import sys

# (묻는 사람, 대화 키, 질문, 유형, 시각, 답변, 위키 출처 제목들, 일정 출처 제목들)
CHATS = [
    ("seongho.bae@ajt.com", "chat-0801-a", "연차는 1년에 며칠 부여돼?", "wiki",
     "2026-08-01 10:12:00",
     "연간 **20일** 부여됩니다. 2024년 3월 인사위원회 의결로 기존 15일에서 상향되었고 "
     "2024년 4월 1일부터 적용됩니다.\n\n"
     "| 구분 | 부여 일수 | 조건 |\n|---|---|---|\n"
     "| 1년 이상 | 20일 | 소정근로일의 80% 이상 출근 |\n"
     "| 1년 미만 | 월 1일 | 해당 월 개근 |\n"
     "| 3년 이상 | 매 2년마다 +1일 | 가산 포함 최대 25일 |\n\n"
     "사용은 사용일 3일 전까지 결재 시스템에 신청하고 부서장 승인을 받으시면 됩니다.",
     ["연차유급휴가 규정"], []),

    ("seongho.bae@ajt.com", "chat-0801-a", "그럼 반차도 연차에서 까여?", "wiki",
     "2026-08-01 10:14:00",
     "네, 반차도 연차유급휴가에서 차감됩니다. 하루를 오전·오후로 나누어 쓰는 것이라 "
     "0.5일로 처리됩니다.\n\n다만 **종합건강검진을 받는 날은 유급으로 처리되며 연차를 "
     "차감하지 않습니다.**",
     ["연차유급휴가 규정", "복지제도 안내"], []),

    ("jihwan.oh@ajt.com", "chat-0803-a", "주식 옵션 베스팅 조건이 어떻게 되나요?", "wiki",
     "2026-08-03 09:40:00",
     "주식 옵션 베스팅 조건은 다음과 같습니다.\n\n"
     "| 항목 | 내용 |\n|---|---|\n"
     "| 베스팅 기간 | 4년, 1년 클리프(Cliff) |\n"
     "| 베스팅 시작 | 입사일 기준 (수습 기간 이후가 아님) |\n"
     "| 퇴사 후 행사 기간 | **퇴사 후 8년 이내** |\n\n"
     "재직 중인 구성원은 매년 리프레시 대상이며, 현재 역할 신규 부여액의 18~25% 수준으로 "
     "개인 성과에 따라 달라집니다. 행사가는 제3자 가치평가로 정해져 채용 시점에는 "
     "확정할 수 없습니다.",
     ["주식 옵션(Equity) 안내"], []),

    ("mingyu.jung@ajt.com", "chat-0804-a", "수습 기간은 몇 개월인가요?", "wiki",
     "2026-08-04 11:05:00",
     "일반 구성원은 **3개월**입니다. 영업직은 영업 사이클 특성상 3개월 안에 계약 성사 "
     "역량을 판단하기 어려워 6개월이 적용됩니다.\n\n"
     "수습 기간 중에는 구성원이 계약 종료를 원하면 1주일 전 통보로 가능하고, 회사가 "
     "종료를 결정하면 4주치 기본급을 지급합니다. 수습 종료 시 별도 통보는 없으며 "
     "30/60/90일 체크인으로 진행 상황을 공유합니다.",
     ["수습 기간·퇴직금·계약 및 급여 지급"], []),

    ("seojun.kim@ajt.com", "chat-0805-a", "재택근무 일주일에 며칠까지 쓸 수 있어?", "wiki",
     "2026-08-05 14:22:00",
     "**주 2일**까지 쓸 수 있어요. 2024년 7월 시범 운영을 거쳐 2024년 10월 1일부터 "
     "정식 도입된 제도예요.\n\n"
     "- 재택 사용일을 팀 내 일정 공유 도구에 미리 등록하고 팀장 승인을 받으세요\n"
     "- 재택 중에도 **집중근무시간(10~16시)** 은 사무실 근무와 똑같이 적용돼요\n"
     "- 사내 메신저 응답은 근무시간 내 상시 유지하면 됩니다\n\n"
     "회사가 재택 여부를 확인할 목적으로 협업 도구 접속 로그를 따로 모으지는 않아요.",
     ["근무시간 및 근무형태 안내"], []),

    ("haeun.lee@ajt.com", "chat-0805-b", "출근 시간 조정할 수 있나요?", "wiki",
     "2026-08-05 16:40:00",
     "네, **시차출퇴근제**로 08시부터 10시 사이에서 출근시각을 고를 수 있어요. "
     "퇴근은 고른 출근시각에 9시간을 더한 시각입니다 (08:30 출근이면 17:30 퇴근).\n\n"
     "변경은 월 단위로 신청하고 부서장 승인을 받으면 되고, 급하면 사후 신청도 됩니다.\n\n"
     "다만 **통근버스는 본사 08시 40분 도착 기준으로만 운행**해서, 그보다 이른 출근을 "
     "고르면 이용할 수 없어요.",
     ["근무시간 및 근무형태 안내", "복지제도 안내"], []),

    ("harin.song@ajt.com", "chat-0806-a", "사내 헬스장 이용 시간이 어떻게 되나요?", "wiki",
     "2026-08-06 09:15:00",
     "**지하 1층**에 있고 **평일 07시부터 22시까지** 열려 있어요. 이용료는 없고 사원증 "
     "태깅으로 들어가면 됩니다.\n\n"
     "샤워실과 운동복은 무료인데 운동복은 당일 반납이 원칙이에요. 개인 사물함은 분기마다 "
     "신청받아 추첨으로 배정합니다.",
     ["복지제도 안내"], []),

    ("chaea.yoon@ajt.com", "chat-0806-b", "통근버스 노선 알려주세요", "mixed",
     "2026-08-06 09:50:00",
     "평일 아침에 **강남·판교·잠실 3개 노선**을 운행하고, 각 노선은 본사 **08시 40분 "
     "도착** 기준으로 편성돼 있어요.\n\n"
     "- 탑승은 사전 등록제예요. 총무팀에 신청한 뒤 사원증을 태깅해 이용합니다\n"
     "- 좌석이 부족한 노선은 신청 순서대로 배정돼요\n"
     "- **퇴근 시간대에는 운행하지 않습니다**\n\n"
     "9월 1일부터 세 노선의 정차지가 조정될 예정이에요. 도착 시각 기준은 그대로입니다.",
     ["복지제도 안내"], ["통근버스 노선 개편 시행"]),

    ("chaewon.lim@ajt.com", "chat-0806-c", "경조사 지원기준 알려줘", "wiki",
     "2026-08-06 13:20:00",
     "경조사 지원 기준은 아래와 같아요.\n\n"
     "| 구분 | 경조금 | 휴가 |\n|---|---|---|\n"
     "| 결혼 (본인) | 100만 원 | 5일 |\n"
     "| 결혼 (자녀) | 20만 원 | 1일 |\n"
     "| 조사 (부모) | 100만 원 | 5일 |\n"
     "| 출산 (배우자) | — | 10일 |\n"
     "| 회갑 (부모) | 20만 원 | — |\n\n"
     "청첩장·사망진단서·출생증명서 같은 증빙을 인사팀에 제출하면 경조금 지급과 휴가 "
     "처리가 됩니다. 사본이나 사진도 인정돼요.",
     ["경조사 지원 기준"], []),

    ("taeho.moon@ajt.com", "chat-0807-a", "도서 구매는 월 얼마까지 지원돼?", "wiki",
     "2026-08-07 10:30:00",
     "**월 5만 원 이하**까지는 별도 승인 없이 구매할 수 있어요. 오디오북과 팟캐스트도 "
     "같은 예산으로 살 수 있습니다.\n\n"
     "담당 분야에 국한되지 않고 업무와 느슨하게 관련되면 충분해요. 매월 열리는 북클럽 "
     "**BookAJT** 도서도 예산에 포함됩니다.\n\n"
     "교육 예산은 별도로 **연 100만 원**이 직급·연차 관계없이 모두에게 지원돼요.",
     ["교육 및 도서 지원"], []),

    ("jiwoo.nam@ajt.com", "chat-0808-a", "브랜드 색상 코드가 뭐야?", "wiki",
     "2026-08-08 11:12:00",
     "AJT 브랜드 색상은 아래와 같아요.\n\n"
     "| 이름 | HEX | 용도 |\n|---|---|---|\n"
     "| AJT 블루 (주 색상) | `#1B4DFF` | 로고, 주요 강조 |\n"
     "| AJT 블루 다크 | `#1339CC` | 어두운 배경에서의 로고 |\n"
     "| 잉크 | `#0F1222` | 로고 흑백 버전, 본문 텍스트 |\n"
     "| 페이퍼 | `#FFFFFF` | 로고 반전 버전 |\n\n"
     "**AJT 블루 다크는 어두운 배경에 로고를 얹을 때 쓰는 브랜드색**이고, 다크모드 화면 "
     "UI의 프라이머리 색과는 별개예요. 화면용 다크모드 색은 디자인 시스템 가이드의 "
     "대응 표를 따릅니다.",
     ["브랜드 사용 규정"], []),

    ("yerin.jung@ajt.com", "chat-0808-b", "로고를 제휴사에 줘도 되나요?", "wiki",
     "2026-08-08 15:05:00",
     "**디자인팀장 승인을 받아야** 제공할 수 있어요. 승인 없이 원본 파일을 전달하면 "
     "안 됩니다.\n\n"
     "- 신청서에 제공 대상·사용 목적·노출 매체·사용 기간을 적어 주세요\n"
     "- 외부에 주는 파일은 **PNG 와 SVG 로 한정**돼요\n"
     "- **서체 원본 파일은 어떤 경우에도 제공할 수 없습니다** (사용권이 회사에 한정)\n"
     "- 제공할 때 여백·최소 크기·금지 사용 조항을 함께 전달합니다",
     ["브랜드 자산 배포 및 외부 제공 기준", "브랜드 사용 규정"], []),

    ("doyun.park@ajt.com", "chat-0809-a", "건강검진 언제부터 받을 수 있어?", "mixed",
     "2026-08-09 09:30:00",
     "**9월 한 달간**이 올해 종합건강검진 기간이에요.\n\n"
     "- 연 1회 지원되고 **비용은 회사가 전액 부담**합니다\n"
     "- 만 35세 이상은 상급 검진 항목이 추가로 지원돼요\n"
     "- 배우자 검진은 본인 검진의 50% 범위에서 지원됩니다\n"
     "- **검진 당일은 유급이라 연차를 차감하지 않아요**\n\n"
     "별도 신청 없이 자동 적용되고, 예약 안내는 9월 초에 인사팀에서 발송합니다.",
     ["복지제도 안내"], ["2026년 종합건강검진 기간", "건강검진 예약 안내 발송"]),

    ("seojun.kim@ajt.com", "chat-0809-b", "이번 달 개발부 일정 알려줘", "schedule",
     "2026-08-09 10:05:00",
     "8월 개발부 일정은 아래와 같아요.\n\n"
     "| 날짜 | 일정 |\n|---|---|\n"
     "| 8월 10일 (월) | 스프린트 계획 회의 (10:00~12:00, 3층 소회의실) |\n"
     "| 8월 12일 (수) | 코드 프리즈 |\n"
     "| 8월 13일 (목) | 정기 배포 (19:00~21:00) |\n"
     "| 8월 19일 (수) | 기술 세미나 — 검색 색인 구조 (16:00~17:00) |\n"
     "| 8월 21일 (금) | 스프린트 회고 (15:00~16:30) |\n"
     "| 8월 24일 (월) | 스프린트 계획 회의 |\n"
     "| 8월 27일 (목) | 정기 배포 |\n\n"
     "전사 일정으로는 8월 17일 헬스장 휴관, 8월 28일 전사 타운홀이 있어요.",
     [], ["스프린트 계획 회의", "코드 프리즈", "정기 배포",
          "기술 세미나 — 검색 색인 구조", "스프린트 회고"]),

    ("dain.seo@ajt.com", "chat-0809-c", "출장 가면 식비는 얼마까지 나와?", "wiki",
     "2026-08-09 14:40:00",
     "출장 식비는 **1일 3만 원 정액**으로 지급돼요. 2024년 3월 인사위원회 의결로 "
     "신설되어 2024년 4월 1일 이후 출발하는 출장부터 적용됩니다.\n\n"
     "교통비는 실비 정산이고 영수증을 첨부하셔야 해요. 자가용을 쓰면 사전 승인된 경로에 "
     "한해 거리비례 유류비가 지급됩니다.",
     ["출장비(여비) 정산 안내"], []),
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

    members = {}
    for line in mysql("SELECT member_id, email, name, department_id FROM member;",
                      args.container, args.db, read=True).strip().splitlines():
        mid, email, name, dept = line.split("\t")
        members[email] = (int(mid), name, int(dept))

    wikis = {}
    for line in mysql("SELECT wiki_id, title, scope_key FROM wiki;",
                      args.container, args.db, read=True).strip().splitlines():
        wid, title, scope = line.split("\t")
        wikis[title] = (int(wid), scope)

    schedules = {}
    for line in mysql("SELECT schedule_id, title FROM schedule;",
                      args.container, args.db, read=True).strip().splitlines():
        sid, title = line.split("\t")
        schedules.setdefault(title, int(sid))

    # 부서 스코프 — 물어본 사람이 볼 수 없는 위키를 출처로 달면 시연에서 눌러도 안 열린다
    dept_scope = {1: "D1", 3: "D3", 4: "D4"}

    problems = []
    for asker, _key, question, kind, _at, _answer, wiki_titles, sched_titles in CHATS:
        if asker not in members:
            problems.append(f"질문자 없음: {asker}"); continue
        _mid, _name, dept = members[asker]
        for t in wiki_titles:
            if t not in wikis:
                problems.append(f"출처 위키 없음: {t} — {question}"); continue
            scope = wikis[t][1]
            if scope != "ALL" and scope != dept_scope.get(dept):
                problems.append(f"권한 밖 위키를 출처로 달았다: {asker} → {t}({scope})")
        for t in sched_titles:
            if t not in schedules:
                problems.append(f"출처 일정 없음: {t} — {question}")
        if kind not in ("wiki", "schedule", "mixed"):
            problems.append(f"질문 유형이 틀렸다: {kind}")
        if not wiki_titles and not sched_titles:
            problems.append(f"출처가 하나도 없다 — {question}")
    if problems:
        print("사전 검사 실패:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1

    convs = len({c[1] for c in CHATS})
    srcs = sum(len(c[6]) + len(c[7]) for c in CHATS)
    print(f"사전 검사 통과 — 질문 {len(CHATS)}건 · 대화 {convs}개 · 출처 {srcs}건")

    if args.dry_run:
        for asker, key, question, kind, at, _a, wt, st in CHATS:
            print(f"  [{kind:8}] {at[:10]} {members[asker][1]:4} {question}")
            print(f"             출처: {', '.join(wt + st) or '없음'}")
        return 0

    statements = ["DELETE FROM answer_source;", "DELETE FROM ai_answer;",
                  "DELETE FROM ai_question;"]
    for i, (asker, key, question, kind, at, answer, wt, st) in enumerate(CHATS, start=1):
        statements.append(
            "INSERT INTO ai_question (ai_question_id, member_id, conversation_key, content, "
            f"question_type, success, created_at) VALUES ({i}, {members[asker][0]}, "
            f"{lit(key)}, {lit(question)}, {lit(kind)}, 1, {lit(at)});")
        statements.append(
            "INSERT INTO ai_answer (ai_answer_id, ai_question_id, content, created_at) "
            f"VALUES ({i}, {i}, {lit(answer)}, {lit(at)});")
        for t in wt:
            statements.append(
                "INSERT INTO answer_source (ai_answer_id, wiki_id, schedule_id, source_title) "
                f"VALUES ({i}, {wikis[t][0]}, NULL, {lit(t)});")
        for t in st:
            statements.append(
                "INSERT INTO answer_source (ai_answer_id, wiki_id, schedule_id, source_title) "
                f"VALUES ({i}, NULL, {schedules[t]}, {lit(t)});")

    mysql("START TRANSACTION;\n" + "\n".join(statements) + "\nCOMMIT;", args.container, args.db)

    empty = mysql("SELECT COUNT(*) FROM ai_answer a LEFT JOIN answer_source s "
                  "ON s.ai_answer_id = a.ai_answer_id WHERE s.answer_source_id IS NULL;",
                  args.container, args.db, read=True).strip()
    if empty != "0":
        print(f"출처가 없는 답변이 {empty}건 남았다", file=sys.stderr)
        return 1
    print(f"반영 완료 — 질문 {len(CHATS)}건 · 출처 {srcs}건 (출처 0건 답변 없음)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
