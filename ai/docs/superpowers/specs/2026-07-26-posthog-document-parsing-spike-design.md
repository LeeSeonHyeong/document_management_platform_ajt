> **이관 주석 (2026-07-28)** — 개인 검증 워크스페이스의 spike
> (`spikes/document-parsing-posthog/`)에서 작성된 기록을 그대로 옮겼다. 본문 경로는
> 당시 기준이다. 결과물은 이 저장소의 `ai/src/document_parser/` 와 `ai/tests/parsing/`
> 으로 들어왔다 (MR !17, S15P11B106-76).

# PostHog 문서 파싱·OCR Spike 설계

- 작성일: 2026-07-26
- 상태: 사용자 승인 설계
- 목적: 전체 LLM Wiki 시스템을 구축하기 전에 TXT, Markdown, PDF, DOCX와 스캔 문서의 텍스트 추출 가능성 및 품질을 LLM API 호출 없이 검증한다.

## 1. 범위

이 spike는 문서 업로드 이후부터 Wiki 변환 이전까지의 파싱 단계만 다룬다.

포함 범위:

- TXT·Markdown UTF-8 텍스트 추출
- 일반 PDF 텍스트 추출
- DOCX 문단·목록·표 텍스트 추출
- 텍스트가 없거나 부족한 PDF 및 이미지 기반 DOCX의 OCR 대체 처리
- 페이지별 추출 방식, 품질 점수, 경고와 실패 사유 반환
- PostHog Markdown 원문을 기준으로 한 자동 품질 평가
- 실제 비식별 문서를 나중에 추가할 수 있는 비공개 코퍼스 영역

제외 범위:

- LLM 호출과 Wiki 생성·수정
- 파일 업로드 API, DB 저장, 비동기 작업 큐
- 검색, 임베딩과 질의응답
- 한국어 OCR 품질의 최종 보증
- 운영 배포와 수평 확장

## 2. 분리 및 조립 전략

파서는 독립 spike에서 검증하지만 일회용으로 만들지 않는다. 실제 파서 코드는 `document_parser` 패키지로 분리하며, 향후 백엔드 또는 비동기 워커가 동일한 공개 계약을 호출한다.

전체 시스템에서의 위치는 다음과 같다.

```text
파일 업로드
  -> 원본 저장
  -> document_parser.parse(file)
  -> ParseResult 저장
  -> Wiki 변환 에이전트
  -> 검색 및 질의응답
```

파서는 DB, 작업 큐, Wiki 저장소와 LLM에 의존하지 않는다. 호출자는 파일과 파싱 옵션을 전달하고 `ParseResult`만 소비한다.

## 3. 디렉터리 구조

```text
spikes/document-parsing-posthog/
├── README.md
├── pyproject.toml
├── corpus/
│   ├── source/posthog/
│   ├── generated/
│   ├── expected/
│   └── private/
├── src/document_parser/
├── scripts/
├── tests/
├── results/
│   ├── report.md
│   └── artifacts/
└── docs/superpowers/specs/
```

관리 규칙:

- `corpus/source/posthog/`: 고정된 정답 Markdown과 출처 URL·원본 커밋 ID를 저장한다.
- `corpus/generated/`: 스크립트가 생성하는 TXT, PDF, DOCX와 스캔 변형을 저장하며 Git에서는 제외한다.
- `corpus/expected/`: 정규화된 정답 텍스트와 핵심 문구 목록을 저장한다.
- `corpus/private/`: 실제 비식별 사내 문서를 저장하며 디렉터리 내용 전체를 Git에서 제외한다.
- `results/report.md`: 최신 품질 결과를 사람이 읽을 수 있게 저장한다.
- `results/artifacts/`: 추출 원문과 실패 조사 자료를 저장하며 Git에서는 제외한다.

## 4. 코퍼스

초기 공개 코퍼스는 기존 spike에서 사용한 PostHog 문서 중 다음 3개로 제한한다.

1. `onboarding.md`: 긴 본문, 제목, 목록과 링크 검증
2. `company-security.md`: 정책 문장과 계층 구조 검증
3. `time-off-current.md`: 숫자와 정책 핵심 문구 검증

각 원본에서 다음 변형을 결정론적 스크립트로 생성한다.

- UTF-8 TXT
- 텍스트 레이어가 있는 PDF
- 일반 DOCX
- 페이지 전체가 이미지인 스캔 PDF
- 90도 회전된 스캔 PDF
- 저해상도 스캔 PDF
- 텍스트 페이지와 스캔 페이지가 섞인 PDF
- 이미지 기반 DOCX

`time-off-legacy.md`는 파싱 형식 검증에서 내용 중복이 크므로 제외하고 기존 Wiki 충돌 검증에 유지한다.

PostHog 코퍼스는 영어이므로 초기 spike는 파일 처리와 영어 OCR을 검증한다. 한국어 OCR은 실제 비식별 문서가 확보되면 `corpus/private/`의 별도 품질군으로 추가한다.

## 5. 파싱 방식

- TXT·Markdown: 명시적인 UTF-8 읽기를 기본으로 하며 디코딩 실패를 구조화된 오류로 반환한다.
- PDF: 먼저 네이티브 텍스트를 페이지별로 추출한다. 텍스트가 없거나 품질 휴리스틱을 통과하지 못한 페이지만 OCR한다.
- DOCX: 문단, 목록과 표를 문서 순서에 맞게 추출한다. 이미지 기반으로 판정된 문서는 렌더링 가능한 중간 형식으로 변환한 뒤 OCR 경로를 사용한다.
- OCR: 로컬 Tesseract 계열 엔진을 사용한다. 영어는 `eng`, 한국어 실제 문서가 추가되면 `kor+eng`을 사용한다.

초기 후보 구현은 PyMuPDF, python-docx, Tesseract와 pytest 조합이다. 외부 LLM 및 유료 OCR API는 사용하지 않는다.

## 6. 공개 계약

개념적 진입점은 다음과 같다.

```python
parse(file_path, options=None) -> ParseResult
```

`ParseResult`는 최소한 다음 정보를 제공한다.

```json
{
  "text": "추출된 전체 텍스트",
  "pages": [
    { "page": 1, "text": "...", "method": "native", "qualityScore": 0.99 }
  ],
  "usedOcr": false,
  "qualityScore": 0.99,
  "warnings": [],
  "error": null
}
```

계약 규칙:

- 성공과 저품질 성공을 구별한다.
- OCR 사용 여부를 문서 및 페이지 단위로 추적한다.
- 손상 파일, 지원하지 않는 형식, 디코딩 실패와 OCR 실패를 구별한다.
- 부분 성공 시 확보한 텍스트와 실패한 페이지 정보를 함께 반환한다.
- 반환 데이터에는 LLM이 생성한 내용이 포함되지 않는다.

## 7. 품질 평가

정답 Markdown과 추출 결과는 공백, 줄바꿈과 형식 표기를 정규화한 뒤 비교한다. 다음 지표를 기록한다.

- 핵심 문구 검출률
- 정규화 문자 오류율(CER)
- 제목·문단·표의 상대적 순서 보존
- OCR fallback 발동 정확성
- 페이지별 처리 방식
- 전체 및 페이지별 처리 시간
- 실패 유형과 사용자에게 표시 가능한 실패 사유
- 같은 입력을 반복 처리했을 때의 결과 일관성

초기 합격 기준:

- TXT·Markdown: 핵심 문구 100% 검출
- 일반 PDF·DOCX: 핵심 문구 100% 검출
- 깨끗한 영어 스캔: 핵심 문구 95% 이상 검출
- 저품질 스캔: 임계값 미만이면 정상 성공 대신 저품질 또는 실패로 판정
- 손상·미지원 파일: 프로세스가 비정상 종료하지 않고 구조화된 실패를 반환

정확한 CER 및 저품질 임계값은 최초 baseline 실행 결과를 보고 테스트 설정으로 고정한다. 이는 기능 범위를 바꾸는 정책 결정이 아니라 현실적인 품질 기준을 보정하는 절차다.

## 8. 오류 처리

예상 오류 범주는 다음과 같다.

- `unsupported_file_type`
- `decode_failed`
- `corrupt_document`
- `native_extraction_empty`
- `ocr_unavailable`
- `ocr_failed`
- `low_quality`
- `partial_failure`

파서는 원인 예외를 삼키지 않되 외부 계약에는 안정적인 오류 코드, 요약 메시지와 실패 페이지를 반환한다. 디버깅 상세는 테스트 artifact 또는 내부 로그에만 기록한다.

## 9. 재현성과 보안

- 공개 코퍼스의 출처와 원본 커밋을 고정한다.
- 문서 변형은 seed와 변환 옵션을 명시한 스크립트로 재생성한다.
- 생성 파일은 원본과 생성 스크립트로 복원 가능해야 한다.
- 실제 문서는 Git에 추가하지 않으며 테스트 리포트에도 원문을 포함하지 않는다.
- API 키 또는 LLM 토큰 없이 전체 테스트가 실행되어야 한다.

## 10. 완료 조건

다음을 모두 만족하면 spike를 완료로 본다.

1. PostHog 원본 3개의 모든 지정 변형을 재현 가능하게 생성한다.
2. 지원 형식이 공통 `ParseResult` 계약으로 처리된다.
3. 네이티브 추출과 OCR fallback이 페이지 단위로 구분된다.
4. 자동 테스트가 품질과 실패 동작을 검증한다.
5. `results/report.md`가 형식별 정확도, 처리 시간, 실패와 남은 위험을 요약한다.
6. 실제 백엔드에 이식할 파서 코드와 spike 전용 코퍼스 생성 코드가 분리되어 있다.

