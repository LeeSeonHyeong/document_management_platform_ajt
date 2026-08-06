// 일정 파일 대기 항목을 IndexedDB에 담는다 (S15P11B106-276).
//
// 문서 파일은 고르는 즉시 서버로 올라가므로 새로고침에 살아남는다. 일정 파일은 그럴 수 없다 —
// POST /schedule-sources가 공개 범위를 필수로 받고(추출된 일정에 그대로 찍힌다), 파싱·추출까지
// 동기로 진행하기 때문에 분류 전에 서버가 할 일이 없다.
//
// 그래서 브라우저에 담는다. sessionStorage로는 안 된다 — 대기 항목은 `File`을 들고 있고
// `File`은 JSON으로 직렬화되지 않는다. IndexedDB는 structured clone을 쓰므로 `File`을 그대로
// 넣고 꺼낼 수 있다.
//
// 한계: 브라우저·기기를 옮기면 목록이 따라오지 않고, 저장공간을 비우면 사라진다. 일정 파일은
// 공개 부서만 지정하면 바로 처리되어 대기 시간이 짧아 감수한다.

const DB_NAME = 'ajt-schedule-queue'
const DB_VERSION = 1
const FILE_STORE = 'files'
const META_STORE = 'metadata'

function openDatabase() {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB를 쓸 수 없는 환경입니다.'))
      return
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(FILE_STORE)) {
        db.createObjectStore(FILE_STORE, { keyPath: 'documentId' })
      }
      if (!db.objectStoreNames.contains(META_STORE)) {
        db.createObjectStore(META_STORE, { keyPath: 'documentId' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function runTransaction(storeName, mode, work) {
  return openDatabase().then(
    (db) =>
      new Promise((resolve, reject) => {
        const transaction = db.transaction(storeName, mode)
        const store = transaction.objectStore(storeName)
        let result
        try {
          result = work(store)
        } catch (error) {
          transaction.abort()
          db.close()
          reject(error)
          return
        }
        transaction.oncomplete = () => {
          db.close()
          resolve(result?.result ?? result)
        }
        transaction.onerror = () => {
          db.close()
          reject(transaction.error)
        }
        transaction.onabort = () => {
          db.close()
          reject(transaction.error)
        }
      }),
  )
}

// 저장·삭제 실패로 화면이 멈추면 안 된다. 대기 목록을 브라우저에 남기는 것은 편의 기능이고,
// 실패하면 이번 세션 안에서만 유지되는 예전 동작으로 되돌아간다.
function ignoreFailure(promise) {
  return promise.catch(() => undefined)
}

/** 대기 항목을 저장한다. `File`을 그대로 담으므로 새로고침 후 다시 꺼내 쓸 수 있다. */
export function persistScheduleQueueDocuments(documents) {
  const storable = documents.filter((document) => document.sourceFile instanceof File)
  if (storable.length === 0) return Promise.resolve()
  return ignoreFailure(
    runTransaction(FILE_STORE, 'readwrite', (store) => {
      storable.forEach((document) => {
        store.put({
          documentId: document.documentId,
          file: document.sourceFile,
          originalFileName: document.originalFileName,
          fileSize: document.fileSize,
          mimeType: document.mimeType,
          uploadKind: document.uploadKind,
          uploadedAt: document.uploadedAt,
          uploadedBy: document.uploadedBy,
        })
      })
    }),
  )
}

export function persistScheduleQueueMetadata(documentId, changes) {
  return ignoreFailure(
    runTransaction(META_STORE, 'readwrite', (store) => {
      store.put({ documentId, changes })
    }),
  )
}

export function removeScheduleQueueDocuments(documentIds) {
  const ids = [...documentIds]
  if (ids.length === 0) return Promise.resolve()
  return ignoreFailure(
    runTransaction(FILE_STORE, 'readwrite', (store) => {
      ids.forEach((documentId) => store.delete(documentId))
    }),
  ).then(() =>
    ignoreFailure(
      runTransaction(META_STORE, 'readwrite', (store) => {
        ids.forEach((documentId) => store.delete(documentId))
      }),
    ),
  )
}

/**
 * 담아둔 대기 항목을 복원한다. blob URL은 새로고침하면 무효가 되므로 여기서 다시 만든다.
 * 읽기에 실패하면 빈 목록으로 시작한다 — 예전처럼 세션 안에서만 유지된다.
 */
export async function readScheduleQueue() {
  try {
    const [records, metadataRecords] = await Promise.all([
      runTransaction(FILE_STORE, 'readonly', (store) => store.getAll()),
      runTransaction(META_STORE, 'readonly', (store) => store.getAll()),
    ])
    const documents = await Promise.all((records ?? []).map(async (record) => ({
      documentId: record.documentId,
      originalFileName: record.originalFileName,
      fileSize: record.fileSize,
      mimeType: record.mimeType,
      sourceFile: record.file,
      uploadKind: record.uploadKind,
      // 마크다운 미리보기는 파일에서 다시 읽는다(createPreviewDocuments와 같은 규칙).
      previewContent: record.originalFileName?.toLowerCase().endsWith('.md')
        ? await record.file.text()
        : null,
      downloadUrl: URL.createObjectURL(record.file),
      documentCategoryId: null,
      documentCategoryName: null,
      visibilityType: null,
      departments: [],
      status: 'uploaded',
      uploadedAt: record.uploadedAt,
      uploadedBy: record.uploadedBy,
      previewOnly: true,
    })))
    const metadata = Object.fromEntries(
      (metadataRecords ?? []).map((record) => [record.documentId, record.changes]),
    )
    return { documents, metadata }
  } catch {
    return { documents: [], metadata: {} }
  }
}
