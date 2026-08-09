-- 조직을 시연 형태로 재구성한다. ajt_demo 에서만 실행한다.
-- 목표: 총관리자 1 + 개발부·디자인부·인사부 각 (관리자 1 + 사원 4) = 16명

-- 안전장치는 SQL 이 아니라 org.sh 에 있다. MySQL 은 IF() 의 미평가 분기도 파싱 시점에
-- 식별자를 해석하므로 `IF(DATABASE()='ajt_demo','ok',THROW_ERROR)` 는 올바른 DB 에서도
-- Unknown column 으로 죽는다 (MySQL 8.4 에서 실측). 그래서 clone.sh·cleanup.sh 와 같이
-- bash 쪽에서 대상 DB 를 막는다.

-- 재실행 불가 — 1~4번(DELETE·UPDATE)은 두 번째 실행에서 no-op 이 되지만, 5번 INSERT 는
-- member.email·member.employee_no 의 UNIQUE 제약(uk_member_email·uk_member_employee_no,
-- ../docs/db/erd.sql)에 걸려 중복 키 오류로 실패한다. 단일 문장(다중 UNION ALL SELECT)이라
-- 부분 삽입은 없고 org.sh 가 실패를 그대로 보고하니 데이터는 오염되지 않는다.
-- 이미 적용된 상태에서 다시 하려면: 이 파일을 고치지 말고 clone.sh 로 ajt_demo 를 원본에서
-- 새로 복제 → cleanup.sh 로 QA 잔재 제거 → org.sh 순으로 처음부터 다시 만든다.
-- (org.sh 에도 이미 적용된 상태인지 확인하는 가드가 있다 — dev.kim@ajt.com 존재 여부로 판단)

-- 1) 이기획(4)의 챗봇 질문·답변을 먼저 지운다 (FK 역순)
DELETE FROM answer_source
 WHERE ai_answer_id IN (
   SELECT ai_answer_id FROM ai_answer
    WHERE ai_question_id IN (SELECT ai_question_id FROM ai_question WHERE member_id = 4));
DELETE FROM ai_answer
 WHERE ai_question_id IN (SELECT ai_question_id FROM ai_question WHERE member_id = 4);
DELETE FROM ai_question WHERE member_id = 4;

-- 2) 부서장 참조를 끊고 계정을 지운다
UPDATE department SET manager_id = NULL WHERE department_id = 2;
DELETE FROM member WHERE member_id IN (3, 4, 10, 11, 12, 13);

-- 3) 기획부와 D2 스코프를 지운다 (문서·위키·작업 0건)
DELETE FROM document_category WHERE scope_key = 'D2';
DELETE FROM department WHERE department_id = 2;
DELETE FROM wiki_scope WHERE scope_key = 'D2';

-- 4) 최고관리자를 전용 부서로 옮긴다 (지금은 개발부 소속이다)
UPDATE member SET department_id = 6 WHERE member_id = 9;

-- 5) 부서마다 사원 3명씩 추가한다.
--    비밀번호 해시는 홍길동(2)의 것을 복사해 같은 비밀번호로 로그인된다.
INSERT INTO member (department_id, email, name, password_hash, employee_no, role, signup_status, account_status)
SELECT 1, 'dev.kim@ajt.com',     '김서준', password_hash, 'AJT-2026-0101', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 1, 'dev.lee@ajt.com',     '이하은', password_hash, 'AJT-2026-0102', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 1, 'dev.park@ajt.com',    '박도윤', password_hash, 'AJT-2026-0103', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 3, 'design.jung@ajt.com', '정예린', password_hash, 'AJT-2026-0301', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 3, 'design.kang@ajt.com', '강민서', password_hash, 'AJT-2026-0302', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 3, 'design.cho@ajt.com',  '조하늘', password_hash, 'AJT-2026-0303', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 4, 'hr.yoon@ajt.com',     '윤지호', password_hash, 'AJT-2026-0401', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 4, 'hr.lim@ajt.com',      '임채원', password_hash, 'AJT-2026-0402', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2
UNION ALL
SELECT 4, 'hr.seo@ajt.com',      '서다인', password_hash, 'AJT-2026-0403', 'EMPLOYEE', 'APPROVED', 'ACTIVE' FROM member WHERE member_id = 2;
