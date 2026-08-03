import { useQuery } from '@tanstack/react-query'
import { qk } from '@/shared/api/queryKeys'
import { fetchDocumentFile } from '../api'
import { previewKindOf } from './kind'

// 원본 문서 미리보기의 데이터 원본을 한 곳으로 모은다.
//  - 업로드 대기 중(previewOnly) 문서: 손에 있는 File 객체를 그대로 쓴다(서버에 아직 없다).
//  - 저장된 문서: GET /api/v1/documents/:id/file 로 받은 실제 파일 바이트를 쓴다.
//    권한 검사는 서버가 하므로 403 이면 미리보기도 막힌다.
//
// 파일 blob 은 용량이 커서 캐시에 오래 두지 않는다(gcTime 1분, staleTime 5분).
// 같은 문서를 상세 화면과 모달에서 연달아 열 때 재요청만 막는 정도가 목적이다.
export function useDocumentPreview({
  documentId,
  fileName,
  mimeType,
  sourceFile,
  localOnly = false,
  enabled = true,
}) {
  const kind = previewKindOf(fileName, mimeType)
  // previewStorage 는 sessionStorage(JSON)에 저장하므로 새로 고침 뒤 sourceFile 은 File 이 아닌 빈 객체가 된다.
  // 그때는 로컬 파일이 없는 것으로 보고 "원본 파일을 찾을 수 없음"으로 흘려보낸다.
  const useLocalFile = sourceFile instanceof Blob
  const query = useQuery({
    queryKey: qk.documents.file(documentId),
    queryFn: async () => (await fetchDocumentFile(documentId)).blob,
    // localOnly: 아직 서버에 없는 업로드 대기 문서. documentId 가 임시값이라 파일 요청을 보내면 안 된다.
    enabled: enabled && !useLocalFile && !localOnly && Boolean(documentId),
    staleTime: 5 * 60 * 1000,
    gcTime: 60 * 1000,
    retry: 0,
  })

  if (useLocalFile) {
    return { kind, blob: enabled ? sourceFile : null, isLoading: false, error: null }
  }

  return {
    kind,
    blob: query.data ?? null,
    isLoading: query.isLoading,
    error: query.error ?? null,
  }
}
