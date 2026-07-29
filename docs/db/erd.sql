-- AJT MySQL 8.4 LTS schema
-- Application timestamps are stored as UTC DATETIME(6).
-- API enum values are lower snake_case; DB values are upper snake case.
--
-- [DB 스키마 변경 금지 원칙] 이 파일은 DB 스키마의 SSOT다. 임의로 변경하지 말 것.
--   - 코드(엔티티)와 어긋나면 ERD를 정답으로 보고 엔티티를 이 파일에 맞춘다.
--   - 변경이 필요하면 직접 수정하지 말고, 해당 코드에
--     `// TODO(DB): <사유>. erd.sql 변경 필요 — 팀원 합의 후 진행` 주석을 남기고 팀 합의 후 진행한다.

CREATE DATABASE IF NOT EXISTS `ajt`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `ajt`;

CREATE TABLE `department` (
    `department_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `manager_id` BIGINT UNSIGNED NULL,
    `name` VARCHAR(50) NOT NULL,
    PRIMARY KEY (`department_id`),
    UNIQUE KEY `uk_department_name` (`name`),
    UNIQUE KEY `uk_department_manager` (`manager_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `member` (
    `member_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `department_id` BIGINT UNSIGNED NOT NULL,
    `email` VARCHAR(255) NOT NULL,
    `name` VARCHAR(50) NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `employee_no` VARCHAR(20) NULL,
    `role` VARCHAR(30) NOT NULL,
    `signup_status` VARCHAR(30) NOT NULL,
    `account_status` VARCHAR(30) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`member_id`),
    UNIQUE KEY `uk_member_email` (`email`),
    UNIQUE KEY `uk_member_employee_no` (`employee_no`),
    KEY `idx_member_department` (`department_id`),
    CONSTRAINT `fk_member_department`
        FOREIGN KEY (`department_id`) REFERENCES `department` (`department_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_member_role`
        CHECK (`role` IN ('EMPLOYEE', 'ADMIN')),
    CONSTRAINT `chk_member_signup_status`
        CHECK (`signup_status` IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT `chk_member_account_status`
        CHECK (`account_status` IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `department`
    ADD CONSTRAINT `fk_department_manager`
        FOREIGN KEY (`manager_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE SET NULL;

CREATE TABLE `wiki_scope` (
    `scope_key` VARCHAR(255) NOT NULL,
    `visibility_type` VARCHAR(30) NOT NULL,
    `department_refs` JSON NOT NULL DEFAULT (JSON_ARRAY()),
    `index_path` VARCHAR(500) NOT NULL,
    -- 이 공간의 Wiki 가 바뀔 때마다 같은 트랜잭션에서 1 증가시킨다. 버전 이력이 아니라
    -- 변경 감지 카운터다 (과거 버전 조회·복원은 범위 제외). AI 변환 요청 시작에 이 값을
    -- 전달하고 반영 직전에 같은 값인지 확인해, 그 사이 다른 관리자가 채팅으로 같은 Wiki 를
    -- 고쳤으면 반영하지 않고 실패 처리한다.
    `scope_version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`scope_key`),
    CONSTRAINT `chk_wiki_scope_visibility`
        CHECK (`visibility_type` IN ('ALL', 'DEPARTMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `document_category` (
    `document_category_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `scope_key` VARCHAR(255) NOT NULL,
    `name` VARCHAR(50) NOT NULL,
    `description` VARCHAR(1000) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`document_category_id`),
    UNIQUE KEY `uk_document_category_scope_name` (`scope_key`, `name`),
    CONSTRAINT `fk_document_category_scope`
        FOREIGN KEY (`scope_key`) REFERENCES `wiki_scope` (`scope_key`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `document` (
    `document_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `uploader_id` BIGINT UNSIGNED NOT NULL,
    `document_category_id` BIGINT UNSIGNED NOT NULL,
    `scope_key` VARCHAR(255) NOT NULL,
    `original_file_name` VARCHAR(255) NOT NULL,
    `original_path` VARCHAR(500) NOT NULL,
    `parsed_path` VARCHAR(500) NULL,
    `mime_type` VARCHAR(100) NOT NULL,
    `file_size` BIGINT UNSIGNED NOT NULL,
    `description` VARCHAR(1000) NULL,
    `document_wiki_refs` JSON NOT NULL DEFAULT (JSON_ARRAY()),
    `status` VARCHAR(30) NOT NULL,
    `failure_reason` VARCHAR(1000) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`document_id`),
    KEY `idx_document_uploader` (`uploader_id`),
    KEY `idx_document_category` (`document_category_id`),
    KEY `idx_document_scope_status` (`scope_key`, `status`),
    CONSTRAINT `fk_document_uploader`
        FOREIGN KEY (`uploader_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_document_category`
        FOREIGN KEY (`document_category_id`) REFERENCES `document_category` (`document_category_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_document_scope`
        FOREIGN KEY (`scope_key`) REFERENCES `wiki_scope` (`scope_key`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_document_file_size`
        CHECK (`file_size` <= 20971520),
    CONSTRAINT `chk_document_status`
        CHECK (`status` IN (
            'UPLOADED', 'PARSING', 'PROCESSING',
            'COMPLETED', 'FAILED', 'CANCELLED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_job` (
    `job_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `requester_id` BIGINT UNSIGNED NOT NULL,
    `scope_key` VARCHAR(255) NOT NULL,
    `workspace_path` VARCHAR(500) NOT NULL,
    `status` VARCHAR(30) NOT NULL,
    `document_ids` JSON NOT NULL,
    `document_results` JSON NULL,
    `failure_reason` VARCHAR(1000) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `started_at` DATETIME(6) NULL,
    `finished_at` DATETIME(6) NULL,
    PRIMARY KEY (`job_id`),
    KEY `idx_ai_job_requester` (`requester_id`),
    KEY `idx_ai_job_scope_status_created` (`scope_key`, `status`, `created_at`),
    CONSTRAINT `fk_ai_job_requester`
        FOREIGN KEY (`requester_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_job_scope`
        FOREIGN KEY (`scope_key`) REFERENCES `wiki_scope` (`scope_key`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_ai_job_status`
        CHECK (`status` IN (
            'WAITING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `wiki_category` (
    `wiki_category_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `scope_key` VARCHAR(255) NOT NULL,
    `name` VARCHAR(50) NOT NULL,
    `description` VARCHAR(1000) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`wiki_category_id`),
    UNIQUE KEY `uk_wiki_category_scope_name` (`scope_key`, `name`),
    CONSTRAINT `fk_wiki_category_scope`
        FOREIGN KEY (`scope_key`) REFERENCES `wiki_scope` (`scope_key`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `wiki` (
    `wiki_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `wiki_category_id` BIGINT UNSIGNED NOT NULL,
    `scope_key` VARCHAR(255) NOT NULL,
    `title` VARCHAR(200) NOT NULL,
    -- 목차·검색 결과·선택 API가 함께 쓰는 한 줄 요약. 내부 API 계약이 `selectedWikis[].summary`
    -- 를 요구하는데 이 컬럼이 없어 `index.md` 마크다운에서 되읽는 우회를 쓰고 있었다
    -- (Wiki.java 의 TODO(DB)). 목차 파일 형식에 의존하지 않도록 여기 저장한다.
    `summary` VARCHAR(500) NULL,
    `wiki_path` VARCHAR(500) NOT NULL,
    -- 현재 Markdown 본문의 SHA-256. 파일이 정본이므로(DR-001) 이 값은 파일에서 파생된다.
    `content_hash` VARCHAR(64) NOT NULL DEFAULT '',
    -- `wiki_search_chunk` 가 색인한 본문의 SHA-256. `content_hash` 와 다르면 색인이 낡았고
    -- NULL 이면 아직 색인되지 않았다. 반영 실패로 백업을 복원한 경우(DR-008)처럼 파일만
    -- 되돌아간 상태를 탐지하는 유일한 수단이다.
    `search_indexed_hash` VARCHAR(64) NULL,
    `wiki_refs` JSON NOT NULL DEFAULT (JSON_ARRAY()),
    `document_refs` JSON NOT NULL DEFAULT (JSON_ARRAY()),
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`wiki_id`),
    KEY `idx_wiki_category` (`wiki_category_id`),
    KEY `idx_wiki_scope_updated` (`scope_key`, `updated_at`),
    CONSTRAINT `fk_wiki_category`
        FOREIGN KEY (`wiki_category_id`) REFERENCES `wiki_category` (`wiki_category_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_wiki_scope`
        FOREIGN KEY (`scope_key`) REFERENCES `wiki_scope` (`scope_key`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Wiki 본문 전문 검색용 파생 색인. Markdown 파일이 정본이고(DR-001) 이 테이블은 정본에서
-- 언제든 재생성할 수 있다. 관계 테이블이 아니므로 DR-002 와 충돌하지 않는다.
--
-- 페이지 단위가 아니라 청크 단위인 이유: 검색 결과에 헤더 경로(`breadcrumb`)를 함께 돌려줘야
-- 에이전트와 사용자가 페이지 어디에 걸렸는지 안다.
--
-- 한국어는 조사·어미가 붙어 공백 분리 색인이 실패한다 ("연차" 로 "연차를" 을 못 찾는다).
-- 실측: 공백 분리 R@5 0.38 · 3글자 0.50 · 2글자 0.60(정확어 0.95). 그래서 `ngram` 파서를 쓰고
-- `ngram_token_size` 를 2 로 둔다. 이 값은 서버 변수(`my.cnf`)이며 DDL 로 지정할 수 없고,
-- 나중에 바꾸면 FULLTEXT 인덱스 전체를 재구축해야 한다.
--
-- 반영이 완료된 Wiki 만 색인한다. 작업 공간의 검증 전 내용은 넣지 않는다(DR-006).
-- 사원 Wiki 검색과 에이전트 문맥 조회가 이 색인을 공유하며, 각자의 권한 검증을 통과한 뒤
-- 조회한다. 스코프 필터는 SQL 안에서 적용하므로 `scope_key` 를 여기 중복 저장한다.
CREATE TABLE `wiki_search_chunk` (
    `chunk_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `wiki_id` BIGINT UNSIGNED NOT NULL,
    `scope_key` VARCHAR(255) NOT NULL,
    `chunk_index` BIGINT UNSIGNED NOT NULL,
    `breadcrumb` VARCHAR(500) NULL,
    `content` TEXT NOT NULL,
    `content_hash` VARCHAR(64) NOT NULL,
    `indexed_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`chunk_id`),
    UNIQUE KEY `uk_wiki_search_chunk` (`wiki_id`, `chunk_index`),
    KEY `idx_wiki_search_scope` (`scope_key`),
    FULLTEXT KEY `ft_wiki_search_content` (`content`) WITH PARSER ngram,
    CONSTRAINT `fk_wiki_search_chunk_wiki`
        FOREIGN KEY (`wiki_id`) REFERENCES `wiki` (`wiki_id`)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_wiki_search_chunk_scope`
        FOREIGN KEY (`scope_key`) REFERENCES `wiki_scope` (`scope_key`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `wiki_chat_message` (
    `message_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `wiki_id` BIGINT UNSIGNED NULL,
    `wiki_title_snapshot` VARCHAR(200) NOT NULL,
    `member_id` BIGINT UNSIGNED NULL,
    `sender_type` VARCHAR(30) NOT NULL,
    `content` TEXT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`message_id`),
    KEY `idx_wiki_chat_wiki_created` (`wiki_id`, `created_at`),
    KEY `idx_wiki_chat_member` (`member_id`),
    CONSTRAINT `fk_wiki_chat_wiki`
        FOREIGN KEY (`wiki_id`) REFERENCES `wiki` (`wiki_id`)
        ON UPDATE RESTRICT ON DELETE SET NULL,
    CONSTRAINT `fk_wiki_chat_member`
        FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_wiki_chat_sender`
        CHECK (`sender_type` IN ('ADMIN', 'AGENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `schedule` (
    `schedule_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `author_id` BIGINT UNSIGNED NOT NULL,
    `source_group_key` VARCHAR(100) NULL,
    `source_original_path` VARCHAR(500) NULL,
    `source_original_file_name` VARCHAR(255) NULL,
    `source_parsed_path` VARCHAR(500) NULL,
    `title` VARCHAR(200) NOT NULL,
    `content` TEXT NULL,
    `target_text` VARCHAR(500) NULL,
    `location` VARCHAR(200) NULL,
    `visibility_type` VARCHAR(30) NOT NULL,
    `start_at` DATETIME(6) NOT NULL,
    `end_at` DATETIME(6) NOT NULL,
    `status` VARCHAR(30) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`schedule_id`),
    KEY `idx_schedule_author` (`author_id`),
    KEY `idx_schedule_source_group` (`source_group_key`),
    KEY `idx_schedule_period_status` (`start_at`, `end_at`, `status`),
    CONSTRAINT `fk_schedule_author`
        FOREIGN KEY (`author_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_schedule_visibility`
        CHECK (`visibility_type` IN ('ALL', 'DEPARTMENT', 'PERSONAL')),
    CONSTRAINT `chk_schedule_status`
        CHECK (`status` IN ('DRAFT', 'APPROVED')),
    CONSTRAINT `chk_schedule_period`
        CHECK (`end_at` >= `start_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `schedule_department` (
    `schedule_department_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `schedule_id` BIGINT UNSIGNED NOT NULL,
    `department_id` BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (`schedule_department_id`),
    UNIQUE KEY `uk_schedule_department` (`schedule_id`, `department_id`),
    KEY `idx_schedule_department_department` (`department_id`),
    CONSTRAINT `fk_schedule_department_schedule`
        FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`schedule_id`)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_schedule_department_department`
        FOREIGN KEY (`department_id`) REFERENCES `department` (`department_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `inquiry` (
    `inquiry_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `member_id` BIGINT UNSIGNED NOT NULL,
    `assignee_id` BIGINT UNSIGNED NOT NULL,
    `title` VARCHAR(200) NOT NULL,
    `content` TEXT NOT NULL,
    `priority` VARCHAR(30) NOT NULL,
    `status` VARCHAR(30) NOT NULL,
    `attachment_refs` JSON NOT NULL DEFAULT (JSON_ARRAY()),
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`inquiry_id`),
    KEY `idx_inquiry_member_created` (`member_id`, `created_at`),
    KEY `idx_inquiry_assignee_status_created`
        (`assignee_id`, `status`, `created_at`),
    CONSTRAINT `fk_inquiry_member`
        FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_inquiry_assignee`
        FOREIGN KEY (`assignee_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_inquiry_priority`
        CHECK (`priority` IN ('HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT `chk_inquiry_status`
        CHECK (`status` IN ('PENDING', 'DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `inquiry_reply` (
    `inquiry_reply_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `inquiry_id` BIGINT UNSIGNED NOT NULL,
    `member_id` BIGINT UNSIGNED NOT NULL,
    `reply` TEXT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`inquiry_reply_id`),
    UNIQUE KEY `uk_inquiry_reply_inquiry` (`inquiry_id`),
    KEY `idx_inquiry_reply_member` (`member_id`),
    CONSTRAINT `fk_inquiry_reply_inquiry`
        FOREIGN KEY (`inquiry_id`) REFERENCES `inquiry` (`inquiry_id`)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_inquiry_reply_member`
        FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_question` (
    `ai_question_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `member_id` BIGINT UNSIGNED NOT NULL,
    `conversation_key` VARCHAR(100) NOT NULL,
    `content` TEXT NOT NULL,
    `question_type` VARCHAR(30) NULL,
    `success` BOOLEAN NOT NULL DEFAULT FALSE,
    `failure_reason` VARCHAR(1000) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`ai_question_id`),
    KEY `idx_ai_question_member_created` (`member_id`, `created_at`),
    KEY `idx_ai_question_member_conversation_created` (`member_id`, `conversation_key`, `created_at`),
    CONSTRAINT `fk_ai_question_member`
        FOREIGN KEY (`member_id`) REFERENCES `member` (`member_id`)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_ai_question_type`
        CHECK (`question_type` IS NULL OR `question_type` IN ('WIKI', 'SCHEDULE', 'MIXED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_answer` (
    `ai_answer_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `ai_question_id` BIGINT UNSIGNED NOT NULL,
    `content` TEXT NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`ai_answer_id`),
    UNIQUE KEY `uk_ai_answer_question` (`ai_question_id`),
    CONSTRAINT `fk_ai_answer_question`
        FOREIGN KEY (`ai_question_id`) REFERENCES `ai_question` (`ai_question_id`)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `answer_source` (
    `answer_source_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `ai_answer_id` BIGINT UNSIGNED NOT NULL,
    `wiki_id` BIGINT UNSIGNED NULL,
    `schedule_id` BIGINT UNSIGNED NULL,
    `source_title` VARCHAR(255) NOT NULL,
    PRIMARY KEY (`answer_source_id`),
    KEY `idx_answer_source_answer` (`ai_answer_id`),
    KEY `idx_answer_source_wiki` (`wiki_id`),
    KEY `idx_answer_source_schedule` (`schedule_id`),
    CONSTRAINT `fk_answer_source_answer`
        FOREIGN KEY (`ai_answer_id`) REFERENCES `ai_answer` (`ai_answer_id`)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_answer_source_wiki`
        FOREIGN KEY (`wiki_id`) REFERENCES `wiki` (`wiki_id`)
        ON UPDATE RESTRICT ON DELETE SET NULL,
    CONSTRAINT `fk_answer_source_schedule`
        FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`schedule_id`)
        ON UPDATE RESTRICT ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
