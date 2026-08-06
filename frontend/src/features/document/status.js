// 문서 처리 상태 · AI 작업 단계의 표시용 라벨/톤. 작업 진행·요약 화면이 공유한다.
// enums.js(DOCUMENT_STATUS)에는 라벨 맵과 cancelled 값이 없어 화면 표시용으로 여기에 둔다.
export const DOC_STATUS_TONE = {
  waiting: 'neutral',
  uploaded: 'neutral',
  parsing: 'info',
  processing: 'info',
  completed: 'success',
  failed: 'danger',
  cancelled: 'neutral',
}

export const DOC_STATUS_LABEL = {
  // 작업 결과의 status 는 아직 차례가 오지 않은 문서를 waiting 으로 준다(문서 테이블의
  // uploaded 와 다른 축이다). 라벨이 없으면 화면에 영문 값이 그대로 새어 나온다.
  waiting: '대기',
  uploaded: '대기',
  parsing: '파싱 중',
  processing: '처리 중',
  completed: '완료',
  failed: '실패',
  cancelled: '취소',
}

// AI 작업 documentResults.currentStage 값(목/백엔드 공통)에 대한 라벨.
// currentStage는 문서 상태에서 역산한 진행 위치라 실패 지점이 아니다 — 실패한 문서는
// 어디서 죽었든 parsing으로 온다. 실패 지점은 FAILURE_STAGE_LABEL 쪽이다.
export const STAGE_LABEL = {
  // waiting·wiki_pending 이 빠져 있어 화면에 영문 값이 그대로 나왔다(S15P11B106-300).
  // 값 목록은 AiJobProgressDialog 의 STAGE_SEQUENCE 와 같아야 한다.
  waiting: '대기',
  parsing: '문서 파싱',
  wiki_pending: 'Wiki 변환 대기',
  wiki_transform: 'Wiki 변환',
  wiki_applied: 'Wiki 반영',
}

// documentResults.failureStage — AI 오류 응답이 실어 준 실제 실패 지점.
// 값은 계약(docs/api/README.md)이 정한 어휘다.
export const FAILURE_STAGE_LABEL = {
  context_load: '문맥 불러오기',
  agent_timeout: 'AI 처리 시간 초과',
  agent_error: 'AI 처리 오류',
  lint_failed: 'Wiki 검사 실패',
  assemble: '결과 조립',
  scope_changed: '처리 중 Wiki 변경',
}
