# AJT 파일 디렉터리 구조 설계

- 최초 작성일: 2026-07-26
- 최종 수정일: 2026-07-27
- 대상: 1주 MVP
- 저장소 루트: `/data/ajt`

## 1. 설계 원칙

1. 별도 파일 테이블을 만들지 않고 현재 경로를 업무 테이블 또는 첨부파일 JSON에 저장한다.
2. 위키 원본문서와 Wiki는 동일한 `scope_key` 디렉터리에서 관리한다.
3. 원본문서 카테고리는 관리자가, Wiki 카테고리는 AI 에이전트가 관리하며 카테고리는 DB에만 저장한다.
4. 사용자 입력 파일명과 변경 가능한 부서명은 실제 경로에 사용하지 않는다.
5. DB에는 `/data/ajt`를 제외한 상대 경로만 저장한다.
6. 과거 버전 조회와 복구 기능은 두지 않으며 삭제 대상 업무 데이터와 파일은 하드 삭제한다.
7. 파일은 권한을 검사하는 애플리케이션 API를 통해서만 제공한다.

## 2. 전체 구조

```text
/data/ajt/
├── wiki/
│   └── {scopeKey}/
│       ├── sources/
│       │   └── {documentId}/
│       │       ├── original/
│       │       │   └── source.{ext}
│       │       └── parsed/
│       │           └── content.md
│       ├── pages/
│       │   └── {wikiId}.md
│       └── index.md
├── schedule-sources/
│   └── {sourceGroupKey}/
│       ├── original/
│       │   └── source.{ext}
│       └── parsed/
│           └── content.md
├── schedules/
│   └── {scheduleId}/
│       └── attachments/
│           └── {attachmentId}.{ext}
├── inquiries/
│   └── {inquiryId}/
│       └── attachments/
│           └── {attachmentId}.{ext}
└── work/
    └── {jobId}/
        ├── apply-state.json
        ├── input/
        ├── output/
        └── backup/
```

## 3. Wiki 범위와 `scope_key`

위키 공간은 관리자가 문서를 업로드할 때 선택한 부서 집합을 기준으로 생성하고 재사용한다.

- 전체 공개: `ALL`
- 개발부 ID가 1인 경우: `D1`
- 개발부 ID 1과 인사부 ID 2를 함께 선택한 경우: `D1-D2`

부서 ID는 중복을 제거하고 오름차순으로 정렬한다. 따라서 `D1-D2`와 `D2-D1`은 같은 공간이다. `개발부 + 인사부` 문서는 개발부 Wiki와 인사부 Wiki에 각각 복제되는 것이 아니라 두 부서가 공동으로 보는 독립된 `D1-D2` 공간에 들어간다.

`wiki_scope`는 `scope_key`, 공개 유형, 부서 ID JSON과 현재 `index.md` 경로를 저장한다. `document`, `wiki`, `document_category`, `wiki_category`, `ai_job`도 같은 `scope_key`를 사용한다.

## 4. Wiki 원본문서

Wiki 원본문서는 `wiki/{scopeKey}/sources/{documentId}`에 저장한다.

- `original/source.{ext}`: 관리자가 업로드한 현재 원본
- `parsed/content.md`: AI 처리를 위해 원본을 변환한 현재 Markdown

원래 파일명, MIME 타입, 크기, 현재 원본·파싱 상대 경로는 `document`에 저장한다. 공개 범위는 `document.scope_key`로 연결된 `wiki_scope`의 공개 유형과 `department_refs`만 기준으로 판단하며 문서별 부서 관계를 별도 테이블에 중복 저장하지 않는다.

원본문서 카테고리는 관리자가 `scope_key` 안에서 추가·수정·삭제하고 `document_category`에 저장한다. 카테고리명은 파일 경로에 포함하지 않는다.

원본문서 교체 시 작업 공간에서 새 원본, 파싱 파일과 Wiki 변경 결과를 먼저 준비한다. 검증과 DB 반영이 성공하면 현재 파일을 교체하고 이전 파일은 삭제한다. 실패하면 기존 원본문서와 Wiki를 유지한다.

원본문서 삭제 시 해당 문서를 제외한 최신 원본문서 집합으로 그 범위의 Wiki를 다시 분석한다. 반영 성공 후 원본문서 디렉터리를 삭제한다. 삭제 후 되돌리기와 과거 버전 보존은 제공하지 않는다.

공개 범위를 바꾸면 새 범위의 원본문서 카테고리를 다시 선택하고 문서 디렉터리를 새 `scopeKey` 아래로 이동한다. 이후 기존 범위와 새 범위의 Wiki를 각각 재분석한다.

## 5. Wiki 문서와 목차

- Wiki 본문: `wiki/{scopeKey}/pages/{wikiId}.md`
- 범위 목차: `wiki/{scopeKey}/index.md`

Wiki 카테고리는 AI 에이전트가 범위별로 생성·수정·삭제하고 `wiki_category`에 저장한다. 원본문서 카테고리와 Wiki 카테고리는 서로 독립적이다.

Wiki 생성과 재처리는 전역에서 한 작업씩 직렬로 수행한다. 재처리 시점의 해당 `scope_key` 최신 원본문서와 현재 Wiki를 기준으로 분석한다.

관리자는 Wiki 상세 화면의 채팅으로 이상한 내용을 수정해 달라고 요청할 수 있다. 대화는 `wiki_chat_message.wiki_id`로 Wiki에 연결하며, 업로드 묶음 전체의 작업별 채팅은 두지 않는다.

## 6. 일정 원본문서와 추출 일정

일정 업로드 1건마다 `sourceGroupKey`를 발급하고 원본과 파싱 결과를 한 벌만 저장한다.

일정 원본문서는 `TXT`, `MD`, `DOCX`, `PDF`, `CSV`, `XLSX` 형식과 파일당 20MB 이하만 허용한다.

- 원본: `schedule-sources/{sourceGroupKey}/original/source.{ext}`
- 파싱: `schedule-sources/{sourceGroupKey}/parsed/content.md`

한 원본문서에서 추출된 일정은 각각 `schedule` 행으로 저장한다. 각 행에는 같은 `source_group_key`, `source_original_path`, `source_parsed_path`를 저장하므로 별도 일정 원본문서 테이블은 만들지 않는다.

거부된 초안은 즉시 하드 삭제한다. 같은 원본문서에서 나온 다른 일정이 남아 있으면 원본과 파싱 파일을 유지하고, 마지막 일정이 삭제되거나 모든 초안이 거부되면 해당 `schedule-sources/{sourceGroupKey}`도 삭제한다. 수동 일정과 개인 일정에는 원본문서 경로가 없다.

## 7. 일정·문의 첨부파일

일정 첨부파일은 일정별로 `schedules/{scheduleId}/attachments`에, 문의 첨부파일은 문의별로 `inquiries/{inquiryId}/attachments`에 저장한다.

일정 첨부파일은 `TXT`, `MD`, `DOCX`, `PDF`, `CSV`, `XLSX` 형식만 최대 20개까지 허용한다. 문의 첨부파일은 이미지 파일인 `PNG`, `JPG`, `JPEG`만 최대 5개까지 허용한다. 두 종류 모두 파일당 최대 20MB, 요청당 총 100MB로 제한한다.

원래 파일명, 상대 경로, MIME 타입과 크기는 각각 `schedule.attachment_refs`, `inquiry.attachment_refs` JSON에 저장한다. 일정이나 문의를 삭제하면 첨부파일 디렉터리도 하드 삭제한다.

## 8. AI 작업 공간

AI 변환 입력과 검증 전 출력은 `work/{jobId}`에 격리한다.

- `apply-state.json`: 문서별 반영 단계, 대상 ID와 백업 위치
- `input/`: 업로드 또는 교체 입력
- `output/`: 검증 전 파싱 파일, Wiki 본문과 목차
- `backup/`: 반영 중 롤백에 사용할 기존 서비스 파일

검증된 파일만 서비스 경로에 반영한다. 성공 작업 공간은 반영 직후 삭제하고 실패·관리자 중단 작업 공간은 3일 뒤 자동 하드 삭제한다. 서버 재시작 시 `ai_job` 상태와 작업 공간 존재 여부를 확인해 처리 중 작업을 재처리 가능한 실패 상태로 전환한다.

## 9. DB에 저장하는 경로 예시

```text
wiki/D1-D2/sources/101/original/source.pdf
wiki/D1-D2/sources/101/parsed/content.md
wiki/D1-D2/pages/3001.md
wiki/D1-D2/index.md
schedule-sources/sg-201/original/source.docx
schedule-sources/sg-201/parsed/content.md
schedules/1001/attachments/4001.pdf
inquiries/2001/attachments/5001.png
work/9001
```

애플리케이션은 `FILE_STORAGE_ROOT=/data/ajt`와 상대 경로를 결합한다. 경로를 정규화한 결과가 저장소 루트 밖으로 벗어나면 요청을 거부한다. 삭제는 DB가 가리키는 정확한 상대 경로만 대상으로 수행한다.

## 10. MVP 범위 밖

다음 항목은 1주 MVP에 포함하지 않는다.

- S3·MinIO 같은 객체 저장소
- 파일 및 Wiki 과거 버전 조회·복구
- 별도 파일·첨부파일·일정 원본문서 테이블
- 업로드 작업 단위의 채팅방
