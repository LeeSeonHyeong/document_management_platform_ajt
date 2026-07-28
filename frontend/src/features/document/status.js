// 문서 처리 상태 · AI 작업 단계의 표시용 라벨/톤. 작업 진행·요약 화면이 공유한다.
// enums.js(DOCUMENT_STATUS)에는 라벨 맵과 cancelled 값이 없어 화면 표시용으로 여기에 둔다.
export const DOC_STATUS_TONE = {
  uploaded: 'neutral',
  parsing: 'info',
  processing: 'info',
  completed: 'success',
  failed: 'danger',
  cancelled: 'neutral',
}

export const DOC_STATUS_LABEL = {
  uploaded: '대기',
  parsing: '파싱 중',
  processing: '처리 중',
  completed: '완료',
  failed: '실패',
  cancelled: '취소',
}

// AI 작업 documentResults.currentStage 값(목/백엔드 공통)에 대한 라벨.
export const STAGE_LABEL = {
  parsing: '문서 파싱',
  wiki_transform: 'Wiki 변환',
  wiki_applied: 'Wiki 반영',
}
