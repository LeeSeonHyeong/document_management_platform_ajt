# 한국어 데모 코퍼스 — corpus-ko-wiki

이 코퍼스는 한국어 사내 규정 문서 8개로 구성된 시연·회귀용 세트다. 목적은 두 가지다.
첫째, 문서 전반에 걸쳐 값이 갱신되거나(연차 15일→20일) 한 주제가 여러 문서에 조각나
흩어져 있는(출장비) 상황에서, 페이지 단위로 통합·갱신하는 **위키**가 청크 단위로 흩어진
조각을 그대로 반환하는 **RAG**보다 우위에 있음을 보여준다. 둘째, DOCX·PDF(이미지 PDF
포함)·TXT·MD 네 가지 원본 형식과 손상 파일 처리까지 파이프라인이 실제로 커버하는지
회귀 테스트로 고정한다. 아래 표는 시연 진행자의 대본이자, 값이 흔들리면 실패해야 하는
회귀 기준이다.

## 의도 매핑 표

| 문서 슬러그 | 형식 | 심은 장치 | 기대 위키 결과 | RAG라면 무엇이 무너지나 |
| --- | --- | --- | --- | --- |
| 01-service-rules-v1 | DOCX | annual-leave 구값(15일), business-trip-expense 조각1(교통비) | (연차) 위키: 연차 페이지 1개, 현재값 20일, 근거 회의록 링크 / (출장비) 위키: 출장비 페이지 1개(교통비+식비+숙박비+한도) | (연차) RAG: 15일·20일 청크를 다 반환해 현재값 불명 / (출장비) RAG: 네 문서 조각을 흩어진 채 반환 |
| 02-hr-committee-minutes-2024-03 | PDF (텍스트 임베드, OCR 아님) | annual-leave 15→20일 의결(회의록, 안건/의결 형식), business-trip-expense 조각2(식비) | 위키: 연차 페이지 1개, 현재값 20일, 근거 회의록 링크 / 위키: 출장비 페이지 1개(교통비+식비+숙박비+한도) | RAG: 15일·20일 청크를 다 반환해 현재값 불명 / RAG: 네 문서 조각을 흩어진 채 반환 |
| 03-service-rules-amendment | MD | annual-leave 현재값(20일), v1.0 참조. 멱등 검증용(동일 문서 재업로드) | 위키: 연차 페이지 1개, 현재값 20일, 근거 회의록 링크. 재업로드해도 페이지 중복 없음 | RAG: 15일·20일 청크를 다 반환해 현재값 불명 |
| 04-business-trip-guide | DOCX | business-trip-expense 조각3(숙박비) | 위키: 출장비 페이지 1개(교통비+식비+숙박비+한도) | RAG: 네 문서 조각을 흩어진 채 반환 |
| 05-expense-faq | TXT | business-trip-expense 조각4(여비 한도·영수증) | 위키: 출장비 페이지 1개(교통비+식비+숙박비+한도) | RAG: 네 문서 조각을 흩어진 채 반환 |
| 06-teamlead-minutes-2024-06 | PDF (텍스트 임베드, OCR 아님) | substitute-holiday 승인 절차, remote-work 주2일 시범(회의록, 안건/의결 형식) | 위키: 대체휴일·재택근무 각각 페이지, 시범 운영 이력이 근거로 남음 | RAG: 회의록 청크 하나만 뽑히면 승인 절차·시범 조건 중 하나가 누락 |
| 07-onboarding-guide | DOCX | annual-leave·remote-work·infosec 요약·교차참조 허브 | 위키: 온보딩 페이지가 연차·재택·정보보안 각 페이지로 링크(허브) — 최신값을 따라감 | RAG: 온보딩 문서 자체의 요약 문구(구버전 수치 포함 가능)를 그대로 반환해 최신 규정과 불일치 |
| 08-infosec-policy | MD | infosec 독립 주제(대조군, 다른 주제와 겹침 없음) | 위키: 정보보안 페이지 1개, 다른 주제와 무관하게 안정적으로 존재 | RAG: (대조군이라 무너질 것이 없음 — 다른 시나리오와의 비교 기준) |

## 주제 키 (TOPICS, ontology.md 와 동일)

annual-leave · half-day · substitute-holiday · business-trip-expense ·
remote-work · salary · onboarding · infosec

## 검증 명령

### 1) 형식 커버리지 (pytest, 오프라인)

```bash
cd ai
uv run pytest tests/corpus/test_corpus_ko_wiki.py -v
```

원문에 심어둔 장치(모순·조각·교차참조·독립 대조군)와, DOCX·PDF·TXT·MD 렌더 결과가
한글 깨짐 없이 파싱되는지, PDF 두 건이 OCR로 떨어지지 않는지, 손상 파일이 정리된
에러 코드로 실패하는지를 확인한다.

### 2) 위키 생성 (로컬, API 키 불필요 — claude-code CLI 런타임)

```bash
cd ai
uv run python experiments/backend_sim.py \
  --runtime claude-code --model claude-sonnet-4-6 \
  --root <임시경로> --report <임시경로>/report.json \
  experiments/corpus-ko-wiki/sources/01-service-rules-v1.md \
  experiments/corpus-ko-wiki/sources/02-hr-committee-minutes-2024-03.md \
  experiments/corpus-ko-wiki/sources/03-service-rules-amendment.md \
  experiments/corpus-ko-wiki/sources/04-business-trip-guide.md \
  experiments/corpus-ko-wiki/sources/05-expense-faq.md
```

`<임시경로>`는 매 실행 새 디렉터리를 준다(예: `/tmp/corpus-ko-demo`). 다섯 문서를 순서대로
반영한 뒤 `<임시경로>/report.json`으로 문서→위키 변경 매핑을, `<임시경로>` 아래 생성된
위키 전문을 확인한다. 연차 페이지가 20일 한 값으로 수렴하고 출장비 페이지가 교통비·
식비·숙박비·한도 네 조각을 모두 담았는지가 통과 기준이다.

### 멱등 시나리오

위 명령 인자 끝에 `03-service-rules-amendment.md`를 한 번 더 추가해 같은 문서를
두 번 준다:

```bash
cd ai
uv run python experiments/backend_sim.py \
  --runtime claude-code --model claude-sonnet-4-6 \
  --root <임시경로> --report <임시경로>/report.json \
  experiments/corpus-ko-wiki/sources/01-service-rules-v1.md \
  experiments/corpus-ko-wiki/sources/02-hr-committee-minutes-2024-03.md \
  experiments/corpus-ko-wiki/sources/03-service-rules-amendment.md \
  experiments/corpus-ko-wiki/sources/04-business-trip-guide.md \
  experiments/corpus-ko-wiki/sources/05-expense-faq.md \
  experiments/corpus-ko-wiki/sources/03-service-rules-amendment.md
```

기대 결과: 연차 페이지가 중복 생성되지 않는다. `03-service-rules-amendment`를 두 번
반영해도 위키에는 여전히 연차 페이지 1개(현재값 20일)만 존재해야 한다 — 중복 페이지가
생기면 멱등성이 깨진 것이다.

## 파이프라인 진입점 주의

`experiments/backend_sim.py`는 원문을 `source.read_text(encoding="utf-8")`로 직접
읽는다. 따라서 검증 명령에는 `experiments/corpus-ko-wiki/sources/*.md`(순수 텍스트
원본)를 준다. `experiments/corpus-ko-wiki/documents/`에 있는 렌더 결과물
(01·04·07의 DOCX, 02·06의 PDF)은 `backend_sim.py`가 읽지 않는다 — 이 파일들은
`document_parser.parse()`를 직접 호출해 형식 파싱 자체를 검증하거나(위 `tests/corpus/
test_corpus_ko_wiki.py`가 이미 함), 실제 FastAPI 서버(`wiki_api`)에 업로드해
파싱→위키 반영 전체 경로를 확인하는 데 쓴다.

## realistic 세트

`sources-realistic/`(SSOT) 는 위 8문서와 같은 슬러그·같은 장치를 유지하되, 실제 사내
문서처럼 목적·정의·부칙·서명란 등 골격과 무관 조항(잡음)을 더한 확장판이다. 최소판
`sources/` 는 빠른 회귀용으로 그대로 두고, realistic 은 시연 optics 와 "잡음 속 신호"
난이도 검증에 쓴다. 렌더는 다음으로 재현한다.

```bash
cd ai
uv run python experiments/corpus-ko-wiki/build.py --realistic
```

산출물은 `documents-realistic/` 에 커밋돼 있어 시연에서 다시 렌더할 필요는 없다.

### realistic 위키 생성 수동 확인 (로컬)

장치가 유지되므로 최소판과 같은 단언을 재사용한다. 최소판 검증 명령의 인자 경로를
`sources/` 에서 `sources-realistic/` 로 바꿔 실행한다.

```bash
cd ai
uv run python experiments/backend_sim.py \
  --runtime claude-code --model claude-sonnet-4-6 \
  --root <임시경로> --report <임시경로>/report.json \
  experiments/corpus-ko-wiki/sources-realistic/01-service-rules-v1.md \
  experiments/corpus-ko-wiki/sources-realistic/02-hr-committee-minutes-2024-03.md \
  experiments/corpus-ko-wiki/sources-realistic/03-service-rules-amendment.md \
  experiments/corpus-ko-wiki/sources-realistic/04-business-trip-guide.md \
  experiments/corpus-ko-wiki/sources-realistic/05-expense-faq.md
```

통과 기준(최소판과 동일): 연차 페이지가 20일 한 값으로 수렴, 출장비 페이지가 교통비·
식비·숙박비·한도 네 조각을 병합, `03` 재업로드 시 연차 페이지 중복 없음. 추가로 잡음
조항(복장·징계·조직개편·경조사 등)이 별도 위키 페이지로 새지 않았는지 육안 확인한다.

## 벌크 확장 seam

이 8개 문서와 그 위의 의도 매핑은 `experiments/corpus-ko-wiki/ontology.md`의
문서×주제 매트릭스를 따른다. 향후 코퍼스를 벌크로 늘리는 생성기는 이 매트릭스만
공유 계약으로 읽고, 각 주제 주변에 문서를 추가로 채워 넣을 수 있다 — 그 벌크
생성기 자체는 이번 작업 범위 밖이다.
