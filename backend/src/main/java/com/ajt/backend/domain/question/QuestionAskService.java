package com.ajt.backend.domain.question;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.SuperAdminChecker;
import com.ajt.backend.domain.question.dto.QuestionAskRequest;
import com.ajt.backend.domain.question.dto.QuestionAskResponse;
import com.ajt.backend.domain.question.dto.QuestionAskSourceResponse;
import com.ajt.backend.domain.question.dto.QuestionEvidenceDocumentResponse;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.schedule.service.ScheduleVisibilityPolicy;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.capability.WikiCapabilityService;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientErrorMapper;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AnswerGenerationRequest;
import com.ajt.backend.global.ai.client.AnswerGenerationResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 챗봇 질문 오케스트레이션입니다. (FR-QNA, POST /api/v1/questions)
 *
 * <p>수정(S15P11B106-169): 계약 1.8.0 에서 <b>AI를 한 번만 부른다.</b> 자료 선택 단계가 없어졌고,
 * 요청에는 이 사용자가 접근할 수 있는 <b>범위별 목차와 그 범위 조회 허가값만</b> 싣는다. 본문과
 * 일정 목록은 싣지 않는다 — 에이전트가 조회 API로 필요한 것을 직접 읽는다.
 *
 * <p>권한은 두 갈래로 건다. Wiki는 범위마다 발급한 <b>허가값</b>으로, 일정은 <b>질문 번호</b>로
 * 판정한다(설계 §3·§7). 그리고 AI가 신고한 출처는 저장 전에 <b>열람 권한을 다시 본다</b> —
 * 읽지 않은 ID를 신고하는 것까지는 조회 권한이 막아주지 않기 때문이다.
 *
 * <p>AI 호출은 트랜잭션 밖에서 한다. 질문·답변 저장만 트랜잭션으로 묶는다.
 */
@Service
public class QuestionAskService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAskService.class);
    private static final int MAX_QUESTION_LENGTH = 1000;
    /** 멀티턴 문맥으로 실어 보낼 이전 질문 수입니다. 요청이 계약 상한을 넘지 않도록 제한합니다. */
    private static final int CONVERSATION_HISTORY_LIMIT = 5;
    /**
     * Wiki 조회 허가값의 유효 기간입니다.
     *
     * <p>챗봇 한 번의 생애를 덮어야 한다. 에이전트 시간 상한(25초)에 재시도 1회를 더한 것보다
     * 넉넉해야 하며, 짧으면 정상 질문이 만료로 실패한다. 5분에서 시작한다.
     */
    private static final Duration CAPABILITY_TTL = Duration.ofMinutes(5);

    private final AiClient aiClient;
    private final MemberRepository memberRepository;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiRepository wikiRepository;
    private final WikiFileStorage wikiFileStorage;
    private final ScheduleRepository scheduleRepository;
    private final DocumentRepository documentRepository;
    private final AiQuestionRepository questionRepository;
    private final QuestionAnswerTransactionService answerTransactionService;
    private final ScheduleVisibilityPolicy scheduleVisibilityPolicy;
    private final WikiCapabilityService wikiCapabilityService;
    private final SuperAdminChecker superAdminChecker;

    public QuestionAskService(
            AiClient aiClient,
            MemberRepository memberRepository,
            WikiScopeRepository wikiScopeRepository,
            WikiRepository wikiRepository,
            WikiFileStorage wikiFileStorage,
            ScheduleRepository scheduleRepository,
            DocumentRepository documentRepository,
            AiQuestionRepository questionRepository,
            QuestionAnswerTransactionService answerTransactionService,
            ScheduleVisibilityPolicy scheduleVisibilityPolicy,
            WikiCapabilityService wikiCapabilityService,
            SuperAdminChecker superAdminChecker
    ) {
        this.aiClient = aiClient;
        this.memberRepository = memberRepository;
        this.wikiScopeRepository = wikiScopeRepository;
        this.wikiRepository = wikiRepository;
        this.wikiFileStorage = wikiFileStorage;
        this.scheduleRepository = scheduleRepository;
        this.documentRepository = documentRepository;
        this.questionRepository = questionRepository;
        this.answerTransactionService = answerTransactionService;
        this.scheduleVisibilityPolicy = scheduleVisibilityPolicy;
        this.wikiCapabilityService = wikiCapabilityService;
        this.superAdminChecker = superAdminChecker;
    }

    public QuestionAskResponse ask(AuthenticatedMember loginMember, QuestionAskRequest request) {
        String question = validateQuestion(request);
        Member member = memberRepository.findById(loginMember.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        String conversationKey = resolveConversationKey(member, request.conversationId());

        // 실패해도 이력에 남긴다(FR-QNA-008). 저장 뒤 questionId를 AI 요청에 싣는다.
        AiQuestion aiQuestion = answerTransactionService.saveQuestion(member, conversationKey, question);

        List<AnswerGenerationRequest.ConversationMessage> history =
                conversationHistory(member.getId(), conversationKey, aiQuestion.getId());
        Set<String> accessibleScopeKeys = accessibleScopeKeys(member);

        try {
            AnswerGenerationResponse answer = generateWithOneRetry(new AnswerGenerationRequest(
                    String.valueOf(aiQuestion.getId()),
                    conversationKey,
                    question,
                    history,
                    wikiIndexes(accessibleScopeKeys)
            ));

            return answerTransactionService.saveAnswer(
                    aiQuestion.getId(),
                    conversationKey,
                    answer,
                    authorizedSources(answer, member, accessibleScopeKeys),
                    this::evidenceDocuments
            );
        } catch (AiClientException exception) {
            answerTransactionService.markFailed(aiQuestion.getId(), failureReason(exception));
            log.warn("AI 답변 생성 실패: questionId={}, failureType={}, code={}",
                    aiQuestion.getId(), exception.failureType(), exception.upstreamCode());
            // 오류 이름별 안내를 그대로 사용자에게 낸다 (설계 §6.7).
            throw new BusinessException(
                    ErrorCode.AI_SERVER_UNAVAILABLE,
                    AiClientErrorMapper.messageFor(exception.upstreamCode()));
        }
    }

    /**
     * 실패하면 한 번 더 시도합니다.
     *
     * <p>재시도가 안전한 근거: AI 는 작업 공간에만 쓰고 반영은 백엔드가 한다. 실패한 실행은 위키에
     * 아무 흔적을 남기지 않는다.
     *
     * <p><b>다시 불러도 결과가 같은 실패는 재시도하지 않는다.</b> 챗봇은 사용자가 기다리는 중이라
     * 25초가 50초가 되는 것이 그대로 체감된다 (설계 §6.8).
     */
    private AnswerGenerationResponse generateWithOneRetry(AnswerGenerationRequest request) {
        try {
            return aiClient.generateAnswer(request);
        } catch (AiClientException first) {
            if (!AiClientErrorMapper.isRetryableAnswerFailure(first.upstreamCode())) {
                throw first;
            }
            log.warn("AI 답변 생성 1차 실패 — 한 번 더 시도한다. questionId={}, code={}",
                    request.questionId(), first.upstreamCode());
            return aiClient.generateAnswer(request);
        }
    }

    private String validateQuestion(QuestionAskRequest request) {
        String question = request == null || request.question() == null ? "" : request.question().strip();
        if (question.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "질문은 비어 있을 수 없습니다.");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "질문은 1000자 이하로 입력해주세요.");
        }
        return question;
    }

    /**
     * 후속 질문이면 기존 대화를, 새 질문이면 서버가 발급한 대화 키를 씁니다.
     * 다른 사용자의 대화는 존재를 숨기기 위해 404로 처리한다(계약).
     */
    private String resolveConversationKey(Member member, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "chat-" + UUID.randomUUID();
        }
        if (!questionRepository.existsByMember_IdAndConversationKey(member.getId(), conversationId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return conversationId;
    }

    /**
     * 같은 대화의 최근 질문·답변을 계약의 {@code conversationMessages} 형태로 만듭니다.
     * 방금 저장한 이번 질문은 제외한다 — 그건 {@code question} 필드로 따로 간다.
     */
    private List<AnswerGenerationRequest.ConversationMessage> conversationHistory(
            long memberId,
            String conversationKey,
            long currentQuestionId
    ) {
        List<AiQuestion> previous = questionRepository
                .findByMember_IdAndConversationKeyOrderByCreatedAtAsc(memberId, conversationKey);
        List<AnswerGenerationRequest.ConversationMessage> messages = new ArrayList<>();
        for (AiQuestion past : previous) {
            if (past.getId() == currentQuestionId || !past.isSuccess()) {
                continue;
            }
            messages.add(new AnswerGenerationRequest.ConversationMessage("user", past.getContent()));
            String answer = answerTransactionService.answerContentOf(past.getId());
            if (answer != null && !answer.isBlank()) {
                messages.add(new AnswerGenerationRequest.ConversationMessage("assistant", answer));
            }
        }
        int from = Math.max(0, messages.size() - CONVERSATION_HISTORY_LIMIT * 2);
        return List.copyOf(messages.subList(from, messages.size()));
    }

    /**
     * 질문자가 근거로 쓸 수 있는 공개범위입니다.
     *
     * <p>사원·부서관리자: 전체 공개(ALL) + 소속 부서를 포함하는 부서 공개 범위. 문서·Wiki 목록
     * 조회와 같은 기준이다(S15P11B106-229·289).
     *
     * <p>최고관리자: 모든 공간(S15P11B106-290). 예전에는 역할을 보지 않아 최고관리자도 소속 부서로
     * 계산했는데, 최고관리자의 소속은 명목상 '최고관리자' 부서라 그 부서를 포함하는 Wiki 공간이
     * 없다 — 결과적으로 전체 공개만 받아 「개발부 규정이 뭐야」 같은 질문에 근거를 찾지 못했다.
     * 최고관리자는 이미 모든 문서·Wiki 를 볼 수 있으므로 새로 열리는 정보는 없다.
     */
    private Set<String> accessibleScopeKeys(Member member) {
        if (superAdminChecker.isSuperAdmin(member)) {
            Set<String> scopeKeys = new HashSet<>();
            scopeKeys.add("ALL");
            wikiScopeRepository.findAll().stream()
                    .map(WikiScope::scopeKey)
                    .forEach(scopeKeys::add);
            return scopeKeys;
        }
        Set<String> scopeKeys = new HashSet<>();
        scopeKeys.add("ALL");
        Long departmentId = member.getDepartment() == null ? null : member.getDepartment().getId();
        if (departmentId != null) {
            wikiScopeRepository.findByVisibilityType(WikiScopeVisibilityType.DEPARTMENT).stream()
                    .filter(scope -> scope.departmentRefs().contains(departmentId))
                    .map(WikiScope::scopeKey)
                    .forEach(scopeKeys::add);
        }
        return scopeKeys;
    }

    /**
     * 범위별 목차와 그 범위 조회 허가값입니다.
     *
     * <p>수정(S15P11B106-169): 챗봇은 범위가 여러 개다(전사 + 소속 부서). 그래서 이미 있는 발급
     * 함수를 <b>범위 수만큼</b> 부른다. 허가값을 같은 행에 담아 목차와 본문 권한이 어긋나지 않게
     * 한다.
     */
    private List<AnswerGenerationRequest.WikiIndex> wikiIndexes(Set<String> scopeKeys) {
        List<AnswerGenerationRequest.WikiIndex> indexes = new ArrayList<>();
        for (String scopeKey : scopeKeys.stream().sorted().toList()) {
            String indexMarkdown = readIndexQuietly(scopeKey);
            if (indexMarkdown == null || indexMarkdown.isBlank()) {
                // 목차가 없는 공간은 고를 것도 없어 후보에서 빼 요청 크기를 줄인다.
                continue;
            }
            // WikiScope의 식별자가 scopeKey다(JpaRepository<WikiScope, String>).
            WikiScope scope = wikiScopeRepository.findById(scopeKey).orElse(null);
            if (scope == null) {
                // 목차 파일은 있는데 공간이 없다. 허가값을 발급할 근거(범위 버전)가 없으므로 뺀다.
                log.warn("Wiki 공간을 찾지 못해 목차에서 제외합니다: scopeKey={}", scopeKey);
                continue;
            }
            String capability = wikiCapabilityService.issue(
                    scopeKey, scope.scopeVersion(), CAPABILITY_TTL);
            indexes.add(new AnswerGenerationRequest.WikiIndex(scopeKey, indexMarkdown, capability));
        }
        return indexes;
    }

    /**
     * AI가 신고한 출처 중 <b>이 사용자가 볼 수 있는 것만</b> 남깁니다.
     *
     * <p>제목은 AI가 준 값을 그대로 쓴다 — 에이전트가 읽은 기록의 제목이고, 계약이 필수로 정한
     * 값이다. 다만 <b>열람 권한은 백엔드가 다시 본다</b>: 허가값과 질문 번호로 AI가 읽을 수 있는
     * 범위는 이미 제한되지만, 읽지 않은 ID를 신고하는 것 자체를 막지는 못한다. 권한 판정은
     * 백엔드 몫이므로 여기서 한 번 더 거른다.
     */
    private AuthorizedSources authorizedSources(
            AnswerGenerationResponse answer,
            Member member,
            Set<String> accessibleScopeKeys
    ) {
        List<Long> wikiIds = new ArrayList<>();
        List<Long> scheduleIds = new ArrayList<>();
        for (AnswerGenerationResponse.Source source : answer.sources()) {
            if (source.isWiki()) {
                parseId(source.wikiId()).ifPresent(wikiIds::add);
            } else if (source.isSchedule()) {
                parseId(source.scheduleId()).ifPresent(scheduleIds::add);
            }
        }

        Map<Long, List<Long>> wikiDocumentRefs = new LinkedHashMap<>();
        if (!wikiIds.isEmpty()) {
            wikiRepository.findAllById(wikiIds).stream()
                    .filter(wiki -> accessibleScopeKeys.contains(wiki.scopeKey()))
                    .forEach(wiki -> wikiDocumentRefs.put(wiki.id(), wiki.documentRefs()));
        }

        Set<Long> readableScheduleIds = new HashSet<>();
        if (!scheduleIds.isEmpty()) {
            // 수정(S15P11B106-171): 공개 부서를 함께 읽는다. 이 메서드는 트랜잭션 밖에서 도므로
            //   findAllById 로 읽으면 detached 엔티티의 lazy 컬렉션을 건드려 500이 난다.
            scheduleRepository.findAllByIdWithDepartments(scheduleIds).stream()
                    .filter(schedule -> scheduleVisibilityPolicy.isReadableBy(schedule, member))
                    .forEach(schedule -> readableScheduleIds.add(schedule.id()));
        }

        return new AuthorizedSources(wikiDocumentRefs, readableScheduleIds);
    }

    private String readIndexQuietly(String scopeKey) {
        try {
            return wikiFileStorage.readIndex(scopeKey);
        } catch (IOException exception) {
            log.warn("목차를 읽지 못해 문맥 후보에서 제외합니다: scopeKey={}", scopeKey);
            return null;
        }
    }

    /** 계약상 ID는 문자열이다. 숫자가 아닌 값은 조회할 수 없으므로 버린다. */
    private Optional<Long> parseId(String id) {
        try {
            return Optional.of(Long.parseLong(id));
        } catch (NumberFormatException | NullPointerException exception) {
            log.warn("숫자가 아닌 출처 ID를 제외합니다: {}", id);
            return Optional.empty();
        }
    }

    /** Wiki 출처의 연결 원본문서입니다. 근거 자료로만 표시하며 본문은 답변에 쓰지 않는다(FR-QNA). */
    private List<QuestionEvidenceDocumentResponse> evidenceDocuments(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return documentRepository.findAllById(documentIds).stream()
                .map(document -> new QuestionEvidenceDocumentResponse(
                        String.valueOf(document.id()),
                        document.originalFileName(),
                        "/api/v1/documents/" + document.id() + "/file"
                ))
                .toList();
    }

    private String failureReason(AiClientException exception) {
        if (exception.upstreamMessage() != null && !exception.upstreamMessage().isBlank()) {
            return exception.upstreamMessage();
        }
        return exception.failureType().name();
    }

    /**
     * 열람 권한을 통과한 출처입니다. 제목은 AI 응답의 값을 쓰므로 담지 않고, <b>인정된 ID와 그
     * Wiki의 연결 원본문서만</b> 담는다.
     */
    public record AuthorizedSources(
            Map<Long, List<Long>> wikiDocumentRefs,
            Set<Long> scheduleIds
    ) {
        public boolean allowsWiki(Long wikiId) {
            return wikiId != null && wikiDocumentRefs.containsKey(wikiId);
        }

        public boolean allowsSchedule(Long scheduleId) {
            return scheduleId != null && scheduleIds.contains(scheduleId);
        }
    }
}
