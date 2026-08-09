const DOCUMENT_ACTIONS = {
  document_added: { label: '문서 추가', countLabel: '추가', tone: 'info' },
  document_replaced: { label: '문서 교체', countLabel: '교체', tone: 'warning' },
  document_removed: { label: '문서 삭제', countLabel: '삭제', tone: 'danger' },
}

const ACTION_ORDER = ['document_added', 'document_replaced', 'document_removed']

export function documentAction(changeType = 'document_added') {
  return DOCUMENT_ACTIONS[changeType] ?? DOCUMENT_ACTIONS.document_added
}

export function actionTypeCountsLabel(results = []) {
  const counts = Object.fromEntries(ACTION_ORDER.map((changeType) => [changeType, 0]))
  for (const result of results) {
    const changeType = DOCUMENT_ACTIONS[result?.changeType] ? result.changeType : 'document_added'
    counts[changeType] += 1
  }
  return ACTION_ORDER
    .filter((changeType) => counts[changeType] > 0)
    .map((changeType) => `${DOCUMENT_ACTIONS[changeType].countLabel} ${counts[changeType]}`)
    .join(' · ')
}

export function affectedWikisFor(result, document) {
  if (Array.isArray(result?.affectedWikis)) {
    return result.affectedWikis.map((wiki) => ({ ...wiki, deleted: wiki.deleted === true }))
  }
  return (document?.relatedWikis ?? []).map((wiki) => ({ ...wiki, deleted: false }))
}
