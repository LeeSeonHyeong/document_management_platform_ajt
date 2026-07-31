package com.ajt.backend.domain.question;

import com.ajt.backend.domain.document.model.Document;
import com.ajt.backend.domain.document.model.WikiScope;
import com.ajt.backend.domain.document.model.WikiScopeVisibilityType;
import com.ajt.backend.domain.document.repository.DocumentRepository;
import com.ajt.backend.domain.document.repository.WikiScopeRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.question.dto.QuestionAskRequest;
import com.ajt.backend.domain.question.dto.QuestionAskResponse;
import com.ajt.backend.domain.question.dto.QuestionAskSourceResponse;
import com.ajt.backend.domain.question.dto.QuestionEvidenceDocumentResponse;
import com.ajt.backend.domain.schedule.model.Schedule;
import com.ajt.backend.domain.schedule.model.ScheduleStatus;
import com.ajt.backend.domain.schedule.model.ScheduleVisibility;
import com.ajt.backend.domain.schedule.repository.ScheduleRepository;
import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import com.ajt.backend.domain.wiki.storage.WikiFileStorage;
import com.ajt.backend.global.ai.client.AiClient;
import com.ajt.backend.global.ai.client.AiClientException;
import com.ajt.backend.global.ai.client.AnswerContextSelectionRequest;
import com.ajt.backend.global.ai.client.AnswerContextSelectionResponse;
import com.ajt.backend.global.ai.client.AnswerGenerationRequest;
import com.ajt.backend.global.ai.client.AnswerGenerationResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 챗봇 질문 오케스트레이션입니다. (FR-QNA, POST /api/v1/questions)
 *
 * <p>계약의 2단계 흐름을 지휘한다.
 * <ol>
 *   <li>이 사용자가 접근할 수 있는 <b>목차와 일정 요약만</b> 보내 필요한 자료 ID를 받는다.</li>
 *   <li>받은 ID의 권한을 <b>다시 검사</b>하고 본문을 읽어 답변을 생성한다.</li>
 * </ol>
 *
 * <p>권한 재검증이 이 서비스의 핵심이다. AI는 권한을 모르고, 1단계 후보에 없는 ID를 낼 수도 있다.
 * 걸러진 자료는 2단계 요청에 실리지 않으므로 답변과 출처에도 나타나지 않는다.
 *
 * <p>AI 호출은 트랜잭션 밖에서 한다. 질문·답변 저장만 트랜잭션으로 묶는다.
 */
@Service
public class QuestionAskService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAskService.class);
    private static final int MAX_QUESTION_LENGTH = 1000;
    /** 멀티턴 문맥으로 실어 보낼 이전 질문 수입니다. 요청이 계약 상한을 넘지 않도록 제한합니다. */
    private static final int CONVERSATION_HISTORY_LIMIT = 5;

    private final AiClient aiClient;
    private final MemberRepository memberRepository;
    private final WikiScopeRepository wikiScopeRepository;
    private final WikiRepository wikiRepository;
    private final WikiFileStorage wikiFileStorage;
    private final ScheduleRepository scheduleRepository;
    private final DocumentRepository documentRepository;
    private final AiQuestionRepository questionRepository;
    private final QuestionAnswerTransactionService answerTransactionService;

    public QuestionAskService(
            AiClient aiClient,
            MemberRepository memberRepository,
            WikiScopeRepository wikiScopeRepository,
            WikiRepository wikiRepository,
            WikiFileStorage wikiFileStorage,
            ScheduleRepository scheduleRepository,
            DocumentRepository documentRepository,
            AiQuestionRepository questionRepository,
            QuestionAnswerTransactionService answerTransactionService
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
    }

    public QuestionAskResponse ask(AuthenticatedMember loginMember, QuestionAskRequest request) {
        String question = validateQuestion(request);
        Member member = memberRepository.findById(loginMember.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        String conversationKey = resolveConversationKey(member, request.conversationId());

        // 실패해도 이력에 남긴다(FR-QNA-008). 저장 뒤 questionId를 AI 요청에 싣는다.
        AiQuestion aiQuestion = answerTransactionService.saveQuestion(member, conversationKey, question);

        List<AnswerContextSelectionRequest.ConversationMessage> history =
                conversationHistory(member.getId(), conversationKey, aiQuestion.getId());
        Set<String> accessibleScopeKeys = accessibleScopeKeys(member);
        List<Schedule> accessibleSchedules = accessibleSchedules(member);

        try {
            AnswerContextSelectionResponse selection = aiClient.selectAnswerContext(
                    new AnswerContextSelectionRequest(
                            String.valueOf(aiQuestion.getId()),
                            conversationKey,
                            question,
                            history,
                            wikiIndexes(accessibleScopeKeys),
                            scheduleSummaries(accessibleSchedules)
                    ));

            // 권한 재검증: AI가 고른 ID 중 이 사용자가 볼 수 있는 것만 남긴다.
            List<Wiki> selectedWikis = verifiedWikis(selection.wikiIds(), accessibleScopeKeys);
            List<Schedule> selectedSchedules = verifiedSchedules(selection.scheduleIds(), accessibleSchedules);

            AnswerGenerationResponse answer = aiClient.generateAnswer(new AnswerGenerationRequest(
                    String.valueOf(aiQuestion.getId()),
                    conversationKey,
                    selection.questionType(),
                    question,
                    history,
                    selectedWikiPayloads(selectedWikis),
                    selectedSchedulePayloads(selectedSchedules)
            ));

            return answerTransactionService.saveAnswer(
                    aiQuestion.getId(),
                    conversationKey,
                    selection.questionType(),
                    answer,
                    verifiedSourceTitles(selectedWikis, selectedSchedules),
                    this::evidenceDocuments
            );
        } catch (AiClientException exception) {
            answerTransactionService.markFailed(aiQuestion.getId(), failureReason(exception));
            log.warn("AI 답변 생성 실패: questionId={}, failureType={}",
                    aiQuestion.getId(), exception.failureType());
            throw new BusinessException(ErrorCode.AI_SERVER_UNAVAILABLE);
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
    private List<AnswerContextSelectionRequest.ConversationMessage> conversationHistory(
            long memberId,
            String conversationKey,
            long currentQuestionId
    ) {
        List<AiQuestion> previous = questionRepository
                .findByMember_IdAndConversationKeyOrderByCreatedAtAsc(memberId, conversationKey);
        List<AnswerContextSelectionRequest.ConversationMessage> messages = new ArrayList<>();
        for (AiQuestion past : previous) {
            if (past.getId() == currentQuestionId || !past.isSuccess()) {
                continue;
            }
            messages.add(new AnswerContextSelectionRequest.ConversationMessage("user", past.getContent()));
            String answer = answerTransactionService.answerContentOf(past.getId());
            if (answer != null && !answer.isBlank()) {
                messages.add(new AnswerContextSelectionRequest.ConversationMessage("assistant", answer));
            }
        }
        int from = Math.max(0, messages.size() - CONVERSATION_HISTORY_LIMIT * 2);
        return List.copyOf(messages.subList(from, messages.size()));
    }

    /** 사원이 접근 가능한 공개범위: 전체 공개(ALL) + 소속 부서를 포함하는 부서 공개 범위. */
    private Set<String> accessibleScopeKeys(Member member) {
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
     * 이 사용자가 볼 수 있는 승인된 일정입니다. 전체 공개, 소속 부서 공개, 본인 개인 일정입니다.
     * 초안(DRAFT)은 관리자 승인 전이라 사용자에게 공개하지 않는다(FR-SCH).
     */
    private List<Schedule> accessibleSchedules(Member member) {
        Long departmentId = member.getDepartment() == null ? null : member.getDepartment().getId();
        return scheduleRepository.findAll().stream()
                .filter(schedule -> schedule.status() == ScheduleStatus.APPROVED)
                .filter(schedule -> isVisibleTo(schedule, member.getId(), departmentId))
                .toList();
    }

    private boolean isVisibleTo(Schedule schedule, long memberId, Long departmentId) {
        ScheduleVisibility visibility = schedule.visibilityType();
        if (visibility == ScheduleVisibility.ALL) {
            return true;
        }
        if (visibility == ScheduleVisibility.PERSONAL) {
            return schedule.authorId() == memberId;
        }
        return departmentId != null && schedule.departmentIds().contains(departmentId);
    }

    private List<AnswerContextSelectionRequest.WikiIndex> wikiIndexes(Set<String> scopeKeys) {
        List<AnswerContextSelectionRequest.WikiIndex> indexes = new ArrayList<>();
        for (String scopeKey : scopeKeys.stream().sorted().toList()) {
            String indexMarkdown = readIndexQuietly(scopeKey);
            if (indexMarkdown == null || indexMarkdown.isBlank()) {
                // 목차가 없는 공간은 고를 것도 없어 후보에서 빼 요청 크기를 줄인다.
                continue;
            }
            indexes.add(new AnswerContextSelectionRequest.WikiIndex(scopeKey, indexMarkdown));
        }
        return indexes;
    }

    private String readIndexQuietly(String scopeKey) {
        try {
            return wikiFileStorage.readIndex(scopeKey);
        } catch (IOException exception) {
            log.warn("목차를 읽지 못해 문맥 후보에서 제외합니다: scopeKey={}", scopeKey);
            return null;
        }
    }

    private List<AnswerContextSelectionRequest.ScheduleSummary> scheduleSummaries(List<Schedule> schedules) {
        return schedules.stream()
                .map(schedule -> new AnswerContextSelectionRequest.ScheduleSummary(
                        String.valueOf(schedule.id()),
                        schedule.title(),
                        schedule.startAt().toString(),
                        schedule.endAt().toString(),
                        schedule.targetText(),
                        schedule.location()
                ))
                .toList();
    }

    /**
     * AI가 고른 Wiki 중 접근 가능한 공간에 실제로 있고 본문이 있는 것만 남깁니다.
     * 후보에 없던 ID를 냈거나 그 사이 삭제된 Wiki는 여기서 걸러진다.
     */
    private List<Wiki> verifiedWikis(List<String> wikiIds, Set<String> accessibleScopeKeys) {
        List<Long> ids = parseIds(wikiIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return wikiRepository.findAllById(ids).stream()
                .filter(wiki -> accessibleScopeKeys.contains(wiki.scopeKey()))
                .filter(wiki -> !readWikiMarkdownQuietly(wiki).isBlank())
                .toList();
    }

    private List<Schedule> verifiedSchedules(List<String> scheduleIds, List<Schedule> accessibleSchedules) {
        Set<Long> ids = new HashSet<>(parseIds(scheduleIds));
        if (ids.isEmpty()) {
            return List.of();
        }
        return accessibleSchedules.stream()
                .filter(schedule -> ids.contains(schedule.id()))
                .toList();
    }

    /** 계약상 ID는 문자열이다. 숫자가 아닌 값은 조회할 수 없으므로 버린다. */
    private List<Long> parseIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        List<Long> parsed = new ArrayList<>();
        for (String id : ids) {
            try {
                parsed.add(Long.parseLong(id));
            } catch (NumberFormatException exception) {
                log.warn("숫자가 아닌 ID를 선택 결과에서 제외합니다: {}", id);
            }
        }
        return parsed;
    }

    private List<AnswerGenerationRequest.SelectedWiki> selectedWikiPayloads(List<Wiki> wikis) {
        return wikis.stream()
                .map(wiki -> new AnswerGenerationRequest.SelectedWiki(
                        String.valueOf(wiki.id()),
                        wiki.title(),
                        readWikiMarkdownQuietly(wiki)
                ))
                .toList();
    }

    private List<AnswerGenerationRequest.SelectedSchedule> selectedSchedulePayloads(List<Schedule> schedules) {
        return schedules.stream()
                .map(schedule -> new AnswerGenerationRequest.SelectedSchedule(
                        String.valueOf(schedule.id()),
                        schedule.title(),
                        schedule.content(),
                        schedule.startAt().toString(),
                        schedule.endAt().toString(),
                        schedule.targetText(),
                        schedule.location()
                ))
                .toList();
    }

    private String readWikiMarkdownQuietly(Wiki wiki) {
        if (wiki.wikiPath() == null || wiki.wikiPath().isBlank()) {
            return "";
        }
        try {
            return wikiFileStorage.readWikiMarkdown(wiki.wikiPath());
        } catch (IOException exception) {
            log.warn("Wiki 본문을 읽지 못해 문맥에서 제외합니다: wikiId={}", wiki.id());
            return "";
        }
    }

    /**
     * 저장·응답에 쓸 출처 제목입니다. AI 응답의 출처는 <b>권한 검증을 통과한 자료만</b> 인정한다.
     * 제목도 AI가 준 값이 아니라 DB 값을 쓴다.
     */
    private VerifiedSources verifiedSourceTitles(List<Wiki> wikis, List<Schedule> schedules) {
        Map<Long, String> wikiTitles = new LinkedHashMap<>();
        wikis.forEach(wiki -> wikiTitles.put(wiki.id(), wiki.title()));
        Map<Long, String> scheduleTitles = new LinkedHashMap<>();
        schedules.forEach(schedule -> scheduleTitles.put(schedule.id(), schedule.title()));
        Map<Long, List<Long>> documentRefs = wikis.stream()
                .collect(Collectors.toMap(Wiki::id, Wiki::documentRefs, (a, b) -> a, LinkedHashMap::new));
        return new VerifiedSources(wikiTitles, scheduleTitles, documentRefs);
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
     * 권한 검증을 통과한 출처입니다. 저장과 응답이 같은 값을 쓰도록 한 곳에 모읍니다.
     */
    public record VerifiedSources(
            Map<Long, String> wikiTitles,
            Map<Long, String> scheduleTitles,
            Map<Long, List<Long>> wikiDocumentRefs
    ) {
    }
}
