package com.ajt.backend.domain.document.storage;

/**
 * 파일 교체 시 새 원본을 최종 경로가 아닌 staging 경로에 먼저 저장한 결과입니다(S15P11B106-146).
 *
 * <p>DB 트랜잭션 안에서는 문서 메타데이터를 {@code finalPath}로 바꾸기만 하고, 커밋이 성공한 뒤에만
 * {@code stagingPath}의 파일을 {@code finalPath}로 이동해 교체를 확정한다. 롤백 시에는 {@code stagingPath}만
 * 정리하고 기존 최종 파일은 건드리지 않는다.
 *
 * @param stagingPath 새 파일이 임시로 저장된 경로
 * @param finalPath   커밋 성공 후 새 파일이 확정될 최종 경로(문서 메타데이터가 가리키는 경로)
 */
public record StagedOriginalFile(String stagingPath, String finalPath) {
}
