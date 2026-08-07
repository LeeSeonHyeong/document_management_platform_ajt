package com.ajt.backend.domain.inquiry;

import com.ajt.backend.domain.inquiry.dto.InquiryAnswerResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryAssigneeListResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryAttachmentDownload;
import com.ajt.backend.domain.inquiry.dto.InquiryCreateRequest;
import com.ajt.backend.domain.inquiry.dto.InquiryListResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryResponse;
import com.ajt.backend.domain.inquiry.dto.InquirySummaryResponse;
import com.ajt.backend.domain.inquiry.storage.InquiryFileStorage;
import com.ajt.backend.domain.member.AccountStatus;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.domain.member.SignupStatus;
import com.ajt.backend.domain.member.SuperAdminChecker;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 문의 등록·조회·삭제와 답변 처리를 담당하는 서비스입니다.
 * 사원은 본인이 등록한 문의만, 관리자는 본인이 담당자로 지정된 문의만 다룰 수 있습니다.
 */
@Service
public class InquiryService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final InquiryRepository inquiryRepository;
    private final InquiryReplyRepository inquiryReplyRepository;
    private final MemberRepository memberRepository;
    private final InquiryFileStorage fileStorage;
    // 수정(S15P11B106-146): 최고관리자(설정 이메일 기준)는 담당자가 아니어도 전체 문의 조회·상세·답변이 가능하다.
    private final SuperAdminChecker superAdminChecker;

    public InquiryService(
            InquiryRepository inquiryRepository,
            InquiryReplyRepository inquiryReplyRepository,
            MemberRepository memberRepository,
            InquiryFileStorage fileStorage,
            SuperAdminChecker superAdminChecker
    ) {
        this.inquiryRepository = inquiryRepository;
        this.inquiryReplyRepository = inquiryReplyRepository;
        this.memberRepository = memberRepository;
        this.fileStorage = fileStorage;
        this.superAdminChecker = superAdminChecker;
    }

    /** 최고관리자(설정 이메일) 여부. role=ADMIN만으로 판단하지 않고 기존 SuperAdminChecker 기준을 재사용한다. */
    private boolean isSuperAdmin(AuthenticatedMember loginMember) {
        return superAdminChecker.isSuperAdmin(loginMember.email(), loginMember.isAdmin());
    }

    /**
     * INQ-09 문의 담당자 후보 조회입니다.
     * 부서 관리자 지정 여부와 무관하게 승인·활성 상태의 모든 관리자를 반환합니다.
     */
    @Transactional(readOnly = true)
    public InquiryAssigneeListResponse findAssignees(AuthenticatedMember loginMember, String keyword) {
        requireLogin(loginMember);
        // TODO(개선): 계약은 keyword 형식 오류 시 400을 명시하지만 현재는 모든 keyword를 통과시킨다.
        //  길이·문자 제한 등 검증 규칙이 확정되면 INVALID_INQUIRY_FILTER로 검증을 추가한다.
        List<Member> candidates = memberRepository.findAll(assigneeSpecification(keyword),
                Sort.by(Sort.Direction.ASC, "name"));
        return InquiryAssigneeListResponse.from(candidates);
    }

    /**
     * INQ-01 문의 등록입니다.
     * 등록 시점에 선택한 담당자의 역할·가입 상태·계정 상태를 다시 검증합니다.
     */
    @Transactional
    public InquiryResponse create(AuthenticatedMember loginMember, InquiryCreateRequest request) {
        requireLogin(loginMember);
        Member author = findMember(loginMember.memberId());
        // TODO(크로스 도메인, 스코프 밖): spec 4.3에 따라 미처리 담당 문의가 남은 관리자의
        //  계정 비활성화·역할 해제는 409로 거부해야 한다. 이는 member/부서 도메인 로직이므로
        //  담당자·회원 관리 작업 시 함께 반영한다. (본 8개 엔드포인트 범위 밖)
        Member assignee = findEligibleAssignee(request.assigneeId());

        Inquiry inquiry;
        try {
            inquiry = Inquiry.create(author, assignee, request.title(), request.content(), request.priority());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY, exception.getMessage());
        }
        inquiryRepository.save(inquiry);

        // TODO(팀 협업): 최대 100MB 첨부 저장을 트랜잭션 내부에서 수행해 DB 커넥션을 오래 점유한다.
        //  문서 업로드 도메인과 동일한 패턴이라, 파일 I/O를 트랜잭션 밖으로 분리할지 팀과 함께 결정한다.
        //  아울러 커밋이 최종 실패하면 이미 기록된 첨부 파일이 고아로 남을 수 있어 정리 전략도 함께 논의한다.
        List<String> storedPaths = new ArrayList<>();
        try {
            List<InquiryAttachment> attachments = storeAttachments(inquiry.getId(), request.attachments(), storedPaths);
            if (!attachments.isEmpty()) {
                inquiry.attach(attachments);
            }
        } catch (RuntimeException exception) {
            deleteStoredFiles(storedPaths);
            throw exception;
        }

        return InquiryResponse.from(inquiry, null);
    }

    /**
     * INQ-02·03 문의 목록 조회입니다.
     * 사원은 본인 등록 문의, 관리자는 본인 담당 문의만 조회 범위로 잡고 필터를 추가합니다.
     */
    @Transactional(readOnly = true)
    public InquiryListResponse findInquiries(
            AuthenticatedMember loginMember,
            Integer page,
            Integer size,
            String status,
            String priority,
            String memberId,
            String createdFrom,
            String createdTo,
            String sort,
            String keyword
    ) {
        requireLogin(loginMember);
        Pageable pageable = createPageable(page, size, sort);
        Specification<Inquiry> specification = inquirySpecification(
                loginMember, isSuperAdmin(loginMember), status, priority, memberId, createdFrom, createdTo, keyword);
        Page<InquirySummaryResponse> result = inquiryRepository.findAll(specification, pageable)
                .map(InquirySummaryResponse::from);
        return InquiryListResponse.from(result);
    }

    /**
     * 문의 상세 조회입니다.
     * 조회 권한이 없는 문의와 존재하지 않는 문의는 동일하게 404로 응답해 존재를 노출하지 않습니다.
     */
    @Transactional(readOnly = true)
    public InquiryResponse getInquiry(AuthenticatedMember loginMember, long inquiryId) {
        requireLogin(loginMember);
        Inquiry inquiry = findVisibleInquiry(loginMember, inquiryId);
        InquiryReply reply = inquiryReplyRepository.findByInquiryId(inquiryId).orElse(null);
        return InquiryResponse.from(inquiry, reply);
    }

    /**
     * 문의 삭제입니다.
     * 작성자 또는 지정 담당자만 삭제할 수 있으며 답변과 첨부 파일까지 하드 삭제합니다.
     */
    @Transactional
    public void deleteInquiry(AuthenticatedMember loginMember, long inquiryId) {
        requireLogin(loginMember);
        Inquiry inquiry = findInquiry(inquiryId);
        if (!isAuthor(loginMember, inquiry) && !isAssignee(loginMember, inquiry)) {
            throw new BusinessException(ErrorCode.INQUIRY_FORBIDDEN);
        }

        List<String> storedPaths = inquiry.getAttachmentRefs().stream()
                .map(InquiryAttachment::storedPath)
                .toList();

        inquiryReplyRepository.deleteByInquiryId(inquiryId);
        inquiryRepository.delete(inquiry);
        // 첨부 파일은 트랜잭션 커밋이 확정된 뒤에 삭제한다.
        // 커밋 전에 지우면 이후 롤백 시 DB에는 문의가 남고 파일만 사라져 정합성이 깨진다.
        deleteStoredFilesAfterCommit(storedPaths);
    }

    /**
     * INQ-07 문의 첨부 이미지 다운로드입니다.
     * 문의 ID와 첨부 ID를 함께 검증하고 권한 없는 문의·파일의 존재를 노출하지 않습니다.
     */
    @Transactional(readOnly = true)
    public InquiryAttachmentDownload downloadAttachment(
            AuthenticatedMember loginMember,
            long inquiryId,
            String attachmentId
    ) {
        requireLogin(loginMember);
        Inquiry inquiry = findVisibleInquiry(loginMember, inquiryId);
        InquiryAttachment attachment = inquiry.getAttachmentRefs().stream()
                .filter(candidate -> candidate.attachmentId().equals(attachmentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));
        Resource resource = fileStorage.load(attachment.storedPath());
        if (!resource.exists()) {
            throw new BusinessException(ErrorCode.INQUIRY_NOT_FOUND);
        }
        return new InquiryAttachmentDownload(resource, attachment.originalFileName(), attachment.mimeType());
    }

    /**
     * INQ-04·06 문의 답변 작성 또는 수정입니다.
     * 지정 담당자만 처리할 수 있으며 답변이 없으면 생성, 있으면 전체 교체하고 상태를 DONE으로 바꿉니다.
     */
    @Transactional
    public InquiryAnswerResponse upsertAnswer(AuthenticatedMember loginMember, long inquiryId, String content) {
        requireLogin(loginMember);
        Inquiry inquiry = findInquiry(inquiryId);
        requireAnswerPermission(loginMember, inquiry);
        // 수정(S15P11B106-146): 답변 작성자는 지정 담당자가 아니라 '실제로 답변한 로그인 사용자'다.
        //   담당자가 답변하면 담당자==로그인 사용자라 기존과 동일하고, 최고관리자가 답변하면 최고관리자로 올바르게 귀속된다.
        Member responder = findMember(loginMember.memberId());

        // 수정(S15P11B106-105): 답변은 문의당 1건(inquiry_id UNIQUE)이라, 두 담당자가 거의 동시에 답변을 등록하면
        //   둘 다 "답변 없음"으로 판단해 각각 INSERT를 시도하고 나중 요청이 UNIQUE 제약에 걸린다. 이 INSERT는 커밋
        //   시점에 flush되므로 예전에는 이 try 밖(커밋 중)에서 터져 500처럼 보였다. save 직후 flush로 충돌을 이
        //   메서드 안에서 확정적으로 드러내, 서버 오류가 아니라 "이미 답변이 등록됨" 업무 충돌(409)로 변환한다.
        InquiryReply reply;
        try {
            reply = inquiryReplyRepository.findByInquiryId(inquiryId)
                    .map(existing -> {
                        existing.updateReply(content);
                        return existing;
                    })
                    .orElseGet(() -> inquiryReplyRepository.save(
                            InquiryReply.create(inquiryId, responder, content)));
            inquiryReplyRepository.flush();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY, exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            // 동시 등록으로 다른 담당자가 먼저 답변을 저장해 inquiry_id UNIQUE 제약에 걸린 경우로 한정한다.
            // (이 흐름에서 발생하는 무결성 위반은 중복 답변뿐이다.)
            throw new BusinessException(ErrorCode.INQUIRY_ANSWER_ALREADY_EXISTS);
        }
        inquiry.markAnswered();
        return InquiryAnswerResponse.from(reply);
    }

    /**
     * INQ-06 문의 답변 삭제입니다.
     * 지정 담당자만 삭제할 수 있으며 답변을 하드 삭제하고 문의를 답변 대기 상태로 되돌립니다.
     */
    @Transactional
    public void deleteAnswer(AuthenticatedMember loginMember, long inquiryId) {
        requireLogin(loginMember);
        Inquiry inquiry = findInquiry(inquiryId);
        requireAnswerPermission(loginMember, inquiry);
        InquiryReply reply = inquiryReplyRepository.findByInquiryId(inquiryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_ANSWER_NOT_FOUND));
        inquiryReplyRepository.delete(reply);
        inquiry.markPending();
    }

    private List<InquiryAttachment> storeAttachments(
            long inquiryId,
            List<MultipartFile> files,
            List<String> storedPaths
    ) {
        List<InquiryAttachment> attachments = new ArrayList<>();
        for (MultipartFile file : files) {
            String attachmentId = UUID.randomUUID().toString();
            String storedPath = storeAttachment(inquiryId, attachmentId, file);
            storedPaths.add(storedPath);
            attachments.add(new InquiryAttachment(
                    attachmentId,
                    file.getOriginalFilename(),
                    storedPath,
                    file.getContentType(),
                    file.getSize()
            ));
        }
        return attachments;
    }

    private String storeAttachment(long inquiryId, String attachmentId, MultipartFile file) {
        try {
            return fileStorage.storeAttachment(inquiryId, attachmentId, file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 첨부 파일 삭제를 트랜잭션 커밋 이후로 미룹니다.
     * 트랜잭션이 없으면(이론상 도달하지 않지만) 즉시 삭제합니다.
     */
    private void deleteStoredFilesAfterCommit(List<String> storedPaths) {
        if (storedPaths.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteStoredFiles(storedPaths);
                }
            });
        } else {
            deleteStoredFiles(storedPaths);
        }
    }

    private void deleteStoredFiles(List<String> storedPaths) {
        // TODO(개선): 파일만 삭제하고 inquiries/{id}/attachments 빈 디렉터리는 남는다.
        //  저장소에 디렉터리 정리 기능을 추가해 함께 제거한다.
        List<String> reversedPaths = new ArrayList<>(storedPaths);
        Collections.reverse(reversedPaths);
        for (String storedPath : reversedPaths) {
            try {
                fileStorage.delete(storedPath);
            } catch (IOException ignored) {
                // 파일 정리는 실패해도 요청 처리를 막지 않습니다.
            }
        }
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private Member findEligibleAssignee(long assigneeId) {
        Member assignee = memberRepository.findById(assigneeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_ASSIGNEE_NOT_ELIGIBLE));
        if (assignee.getRole() != Role.ADMIN
                || assignee.getSignupStatus() != SignupStatus.APPROVED
                || assignee.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INQUIRY_ASSIGNEE_NOT_ELIGIBLE);
        }
        return assignee;
    }

    private Inquiry findInquiry(long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));
    }

    private Inquiry findVisibleInquiry(AuthenticatedMember loginMember, long inquiryId) {
        Inquiry inquiry = findInquiry(inquiryId);
        // 수정(S15P11B106-146): 최고관리자는 작성자·담당자가 아니어도 상세를 조회할 수 있다.
        //   권한 없는 사용자에게는 기존처럼 문의 존재를 숨기기 위해 404(INQUIRY_NOT_FOUND)로 응답한다.
        if (!isSuperAdmin(loginMember) && !isAuthor(loginMember, inquiry) && !isAssignee(loginMember, inquiry)) {
            throw new BusinessException(ErrorCode.INQUIRY_NOT_FOUND);
        }
        return inquiry;
    }

    /**
     * 답변 작성·수정·삭제 권한을 확인합니다(S15P11B106-146).
     * 최고관리자는 담당자가 아니어도 허용하고, 그 외에는 기존처럼 지정 담당자(assignee)만 허용한다.
     */
    private void requireAnswerPermission(AuthenticatedMember loginMember, Inquiry inquiry) {
        if (!isSuperAdmin(loginMember) && !isAssignee(loginMember, inquiry)) {
            throw new BusinessException(ErrorCode.INQUIRY_FORBIDDEN);
        }
    }

    private boolean isAuthor(AuthenticatedMember loginMember, Inquiry inquiry) {
        return inquiry.getAuthor().getId().equals(loginMember.memberId());
    }

    private boolean isAssignee(AuthenticatedMember loginMember, Inquiry inquiry) {
        return inquiry.getAssignee().getId().equals(loginMember.memberId());
    }

    private void requireLogin(AuthenticatedMember loginMember) {
        if (loginMember == null || loginMember.memberId() == null) {
            throw new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
    }

    private Specification<Member> assigneeSpecification(String keyword) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("role"), Role.ADMIN));
            predicates.add(criteriaBuilder.equal(root.get("signupStatus"), SignupStatus.APPROVED));
            predicates.add(criteriaBuilder.equal(root.get("accountStatus"), AccountStatus.ACTIVE));
            // 수정(S15P11B106-146): 최고관리자(설정 이메일)는 담당자로 지정하지 않아도 답변할 수 있으므로 후보에서 제외한다.
            String superAdminEmail = superAdminChecker.superAdminEmail();
            if (superAdminEmail != null && !superAdminEmail.isBlank()) {
                predicates.add(criteriaBuilder.notEqual(
                        criteriaBuilder.lower(root.get("email")), superAdminEmail.toLowerCase(Locale.ROOT)));
            }
            if (keyword != null && !keyword.isBlank()) {
                String likeKeyword = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), likeKeyword));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    @SuppressWarnings("unchecked")
    private Specification<Inquiry> inquirySpecification(
            AuthenticatedMember loginMember,
            boolean superAdmin,
            String status,
            String priority,
            String memberId,
            String createdFrom,
            String createdTo,
            String keyword
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 목록 조회 시 작성자·작성자 부서·담당자·담당자 부서를 함께 로딩해 N+1을 방지한다(S15P11B106-190).
            // count 쿼리에서는 fetch를 사용할 수 없으므로 일반 join으로 대체하고,
            // 조회 쿼리에서는 fetch join을 걸어 같은 join을 조건에도 재사용한다.
            // author.department·assignee.department 모두 단일값(ManyToOne)이라 fetch를 함께 걸어도 행이 늘지 않는다.
            boolean countQuery = query.getResultType() == Long.class || query.getResultType() == long.class;
            Join<Inquiry, Member> authorJoin;
            Join<Inquiry, Member> assigneeJoin;
            if (countQuery) {
                authorJoin = root.join("author", JoinType.INNER);
                assigneeJoin = root.join("assignee", JoinType.INNER);
            } else {
                Fetch<Inquiry, Member> authorFetch = root.fetch("author", JoinType.INNER);
                authorFetch.fetch("department", JoinType.INNER);
                Fetch<Inquiry, Member> assigneeFetch = root.fetch("assignee", JoinType.INNER);
                assigneeFetch.fetch("department", JoinType.INNER);
                authorJoin = (Join<Inquiry, Member>) authorFetch;
                assigneeJoin = (Join<Inquiry, Member>) assigneeFetch;
            }

            // 수정(S15P11B106-146): 최고관리자는 전체 문의를 조회한다(작성자·담당자 제한 없음).
            //   그 외 관리자(부서관리자)는 본인이 담당자인 문의만, 사원은 본인이 작성한 문의만 조회한다.
            if (superAdmin) {
                // 전체 조회: 작성자/담당자 범위 제한을 추가하지 않는다.
            } else if (loginMember.isAdmin()) {
                predicates.add(criteriaBuilder.equal(assigneeJoin.get("id"), loginMember.memberId()));
            } else {
                predicates.add(criteriaBuilder.equal(authorJoin.get("id"), loginMember.memberId()));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("status"), parseStatus(status)));
            }
            if (priority != null && !priority.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("priority"), parsePriority(priority)));
            }
            if (memberId != null && !memberId.isBlank()) {
                predicates.add(criteriaBuilder.equal(authorJoin.get("id"), parseMemberId(memberId)));
            }
            Optional<Instant> from = parseDateStart(createdFrom);
            if (from.isPresent()) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), from.get()));
            }
            Optional<Instant> to = parseDateEndExclusive(createdTo);
            if (to.isPresent()) {
                predicates.add(criteriaBuilder.lessThan(root.get("createdAt"), to.get()));
            }
            // 문의 목록 검색: 제목과 요청자(작성자) 이름을 OR로 부분 일치 검색한다.
            //   직원 현황(MemberService.addKeywordPredicate)과 같이 소문자 변환 후 양쪽 %로 비교한다.
            //   authorJoin은 위에서 이미 만들어 둔 조인을 재사용해 중복 조인을 만들지 않는다.
            if (keyword != null && !keyword.isBlank()) {
                String likeKeyword = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), likeKeyword),
                        criteriaBuilder.like(criteriaBuilder.lower(authorJoin.get("name")), likeKeyword)));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Pageable createPageable(Integer page, Integer size, String sortValue) {
        int safePage = page == null ? DEFAULT_PAGE : page;
        int safeSize = size == null ? DEFAULT_SIZE : size;
        if (safePage < 1) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, "page는 1 이상이어야 합니다.");
        }
        if (safeSize < 1 || safeSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, "size는 1 이상 100 이하여야 합니다.");
        }
        return PageRequest.of(safePage - 1, safeSize, parseSort(sortValue));
    }

    private Sort parseSort(String sortValue) {
        if (sortValue == null || sortValue.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] values = sortValue.split(",", -1);
        String property = values[0].trim();
        if (!List.of("createdAt", "updatedAt").contains(property)) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, "허용되지 않은 정렬 기준입니다.");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (values.length > 1 && !values[1].isBlank()) {
            try {
                direction = Sort.Direction.fromString(values[1]);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, "정렬 방향은 asc 또는 desc만 사용할 수 있습니다.");
            }
        }
        return Sort.by(direction, property);
    }

    private InquiryStatus parseStatus(String value) {
        try {
            return InquiryStatus.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, exception.getMessage());
        }
    }

    private InquiryPriority parsePriority(String value) {
        try {
            return InquiryPriority.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, exception.getMessage());
        }
    }

    private Long parseMemberId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, "등록자 ID는 숫자여야 합니다.");
        }
    }

    private Optional<Instant> parseDateStart(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parseDate(value).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private Optional<Instant> parseDateEndExclusive(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parseDate(value).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.INVALID_INQUIRY_FILTER, "등록 기간은 YYYY-MM-DD 형식이어야 합니다.");
        }
    }
}
