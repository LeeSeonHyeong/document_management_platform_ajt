# AJT AI 서버

원본문서 파싱과 위키 편집 에이전트를 담당하는 FastAPI 서버.
Spring Boot 백엔드만 이 서버를 호출한다 (`/internal/v1`, 계약은
`docs/api/AJT-FastAPI-Internal-API.postman_collection.json`).
AI 서버는 DB와 서비스 파일에 직접 접근하지 않는다.

## 개발 환경

Python 3.12+ 와 [uv](https://docs.astral.sh/uv/)를 사용한다.

```sh
cd ai
uv sync
uv run pytest -m "not ocr"   # OCR 제외 전체 테스트
uv run pytest                # OCR 포함 — 로컬 Tesseract 필요
```

OCR 테스트는 시스템에 [Tesseract](https://github.com/tesseract-ocr/tesseract)와
`eng` traineddata가 설치되어 있어야 한다.

## 구성

| 경로 | 내용 |
| --- | --- |
| `src/document_parser/` | 원본문서 파싱 — TXT·MD·DOCX·PDF, PDF 텍스트 부족 시 OCR 대체 |
| `tests/` | pytest 테스트 |

## 원본문서 파싱

`document_parser.parse(path)` 하나로 진입한다. 반환은 `ParseResult` —
전체 텍스트, 페이지별 결과(`native`/`ocr` 추출 방법 포함), 품질 점수,
경고·오류. 문서 구조는 Markdown으로 보존한다 (DOCX 헤딩 스타일 →
`#` 헤더). 손상 파일·미지원 형식은 예외 대신 `ParseResult.error`
(`corrupt_document`, `unsupported_file_type` 등 코드)로 반환한다.
