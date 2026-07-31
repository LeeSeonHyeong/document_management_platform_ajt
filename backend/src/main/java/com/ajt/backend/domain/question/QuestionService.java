package com.ajt.backend.domain.question;

import com.ajt.backend.domain.question.dto.QuestionHistoryItemResponse;
import com.ajt.backend.domain.question.dto.QuestionHistoryResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문 이력 조회 서비스입니다(FR-QNA-008).
 * 로그인 사용자 본인의 질문·답변·출처만 반환하며, 답변 생성(AI 연동)은 이 도메인의 책임이 아닙니다.
 */
@Service
public class QuestionService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final AiQuestionRepository questionRepository;
    private final AiAnswerRepository answerRepository;
    private final AnswerSourceRepository answerSourceRepository;

    public QuestionService(
            AiQuestionRepository questionRepository,
            AiAnswerRepository answerRepository,
            AnswerSourceRepository answerSourceRepository
    ) {
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.answerSourceRepository = answerSourceRepository;
    }

    /**
     * QNA-08 내 질문 이력 조회입니다.
     * 본인 질문만 최신순으로 조회하고, 대화(conversationId)·질문유형(questionType)으로 필터링합니다.
     * 답변과 출처는 페이지 단위로 배치 조회해 N+1을 피합니다.
     */
    @Transactional(readOnly = true)
    public QuestionHistoryResponse findMyQuestions(
            AuthenticatedMember loginMember,
            String conversationId,
            String questionType,
            Integer page,
            Integer size
    ) {
        Pageable pageable = createPageable(page, size);
        QuestionType type = parseQuestionTypeOrNull(questionType);
        Specification<AiQuestion> specification = specification(loginMember.memberId(), conversationId, type);
        Page<AiQuestion> questions = questionRepository.findAll(specification, pageable);

        Map<Long, AiAnswer> answerByQuestionId = loadAnswers(questions.getContent());
        Map<Long, List<AnswerSource>> sourcesByAnswerId = loadSources(answerByQuestionId.values());

        Page<QuestionHistoryItemResponse> items = questions.map(question -> {
            AiAnswer answer = answerByQuestionId.get(question.getId());
            List<AnswerSource> sources = answer == null
                    ? List.of()
                    : sourcesByAnswerId.getOrDefault(answer.getId(), List.of());
            return QuestionHistoryItemResponse.of(question, answer, sources);
        });
        return QuestionHistoryResponse.from(items);
    }

    private Map<Long, AiAnswer> loadAnswers(List<AiQuestion> questions) {
        List<Long> questionIds = questions.stream().map(AiQuestion::getId).toList();
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        return answerRepository.findByQuestion_IdIn(questionIds).stream()
                .collect(Collectors.toMap(answer -> answer.getQuestion().getId(), answer -> answer));
    }

    private Map<Long, List<AnswerSource>> loadSources(Collection<AiAnswer> answers) {
        List<Long> answerIds = answers.stream().map(AiAnswer::getId).toList();
        if (answerIds.isEmpty()) {
            return Map.of();
        }
        return answerSourceRepository.findByAnswer_IdIn(answerIds).stream()
                .collect(Collectors.groupingBy(source -> source.getAnswer().getId()));
    }

    private Specification<AiQuestion> specification(Long memberId, String conversationId, QuestionType type) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("member").get("id"), memberId));
            if (conversationId != null && !conversationId.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("conversationKey"), conversationId.trim()));
            }
            if (type != null) {
                predicates.add(criteriaBuilder.equal(root.get("questionType"), type));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private QuestionType parseQuestionTypeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return QuestionType.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_QUESTION_FILTER, exception.getMessage());
        }
    }

    private Pageable createPageable(Integer page, Integer size) {
        int safePage = page == null ? DEFAULT_PAGE : page;
        int safeSize = size == null ? DEFAULT_SIZE : size;
        if (safePage < 1) {
            throw new BusinessException(ErrorCode.INVALID_QUESTION_FILTER, "page는 1 이상이어야 합니다.");
        }
        if (safeSize < 1 || safeSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_QUESTION_FILTER, "size는 1 이상 100 이하여야 합니다.");
        }
        // 최신순. 같은 시각이면 id 내림차순으로 안정 정렬한다.
        return PageRequest.of(safePage - 1, safeSize, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        ));
    }
}
