package com.ajt.backend.domain.question;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.question.QuestionAskService.AuthorizedSources;
import com.ajt.backend.domain.question.dto.QuestionAskResponse;
import com.ajt.backend.domain.question.dto.QuestionAskSourceResponse;
import com.ajt.backend.domain.question.dto.QuestionEvidenceDocumentResponse;
import com.ajt.backend.global.ai.client.AnswerGenerationResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문·답변 저장만 담당합니다. AI 호출은 오래 걸려 트랜잭션 밖에 두므로 저장 단위를 여기로 분리합니다.
 */
@Service
public class QuestionAnswerTransactionService {

    private final AiQuestionRepository questionRepository;
    private final AiAnswerRepository answerRepository;
    private final AnswerSourceRepository answerSourceRepository;

    public QuestionAnswerTransactionService(
            AiQuestionRepository questionRepository,
            AiAnswerRepository answerRepository,
            AnswerSourceRepository answerSourceRepository
    ) {
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.answerSourceRepository = answerSourceRepository;
    }

    /**
     * 질문을 먼저 남깁니다. AI 호출 전에 저장해 실패해도 이력이 남고, 발급된 ID를 요청에 싣습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiQuestion saveQuestion(Member member, String conversationKey, String content) {
        return questionRepository.save(AiQuestion.create(
                member, conversationKey, content, null, false, null));
    }

    /**
     * 답변과 출처를 저장하고 응답을 만듭니다.
     *
     * <p>수정(S15P11B106-169): {@code questionType}이 AI 응답으로 옮겨왔으므로 인자로 받지 않고
     * 응답에서 읽는다. 출처 제목도 <b>AI가 준 값을 그대로</b> 저장한다 — 에이전트가 읽은 기록의
     * 제목이고 계약이 필수로 정한 값이다. <b>열람 권한만</b> 백엔드가 다시 걸러
     * ({@code authorized}) 권한 밖 ID는 저장·응답에서 뺀다.
     *
     * <p>{@code sources}가 비어 있는 것은 정상이다 — 근거를 찾지 못한 답변이며
     * {@code answer_source} 행이 0건이 된다 (FR-QNA-007).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuestionAskResponse saveAnswer(
            long questionId,
            String conversationKey,
            AnswerGenerationResponse answer,
            AuthorizedSources authorized,
            Function<List<Long>, List<QuestionEvidenceDocumentResponse>> evidenceLoader
    ) {
        AiQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalStateException("질문을 찾을 수 없습니다: " + questionId));
        question.recordSuccess(QuestionType.fromApiValue(answer.questionType()));

        AiAnswer saved = answerRepository.save(AiAnswer.create(question, answer.answer()));

        List<QuestionAskSourceResponse> sources = new ArrayList<>();
        for (AnswerGenerationResponse.Source source : answer.sources()) {
            if (source.isWiki()) {
                Long wikiId = parseId(source.wikiId());
                if (!authorized.allowsWiki(wikiId)) {
                    // 열람 권한이 없거나 그 사이 삭제된 Wiki다. 저장·응답에서 제외한다.
                    continue;
                }
                answerSourceRepository.save(AnswerSource.wiki(saved, wikiId, source.title()));
                sources.add(QuestionAskSourceResponse.wiki(
                        String.valueOf(wikiId),
                        source.title(),
                        evidenceLoader.apply(authorized.wikiDocumentRefs().getOrDefault(wikiId, List.of()))
                ));
                continue;
            }
            if (source.isSchedule()) {
                Long scheduleId = parseId(source.scheduleId());
                if (!authorized.allowsSchedule(scheduleId)) {
                    continue;
                }
                answerSourceRepository.save(AnswerSource.schedule(saved, scheduleId, source.title()));
                sources.add(QuestionAskSourceResponse.schedule(String.valueOf(scheduleId), source.title()));
            }
        }

        return new QuestionAskResponse(
                conversationKey,
                String.valueOf(questionId),
                answer.questionType(),
                answer.answer(),
                List.copyOf(sources),
                saved.getCreatedAt()
        );
    }

    /** AI 호출이 실패한 질문에 사유를 남깁니다. 답변은 저장하지 않습니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long questionId, String failureReason) {
        questionRepository.findById(questionId)
                .ifPresent(question -> question.recordFailure(failureReason));
    }

    /** 멀티턴 문맥에 쓸 이전 답변 본문입니다. 답변이 없으면 {@code null}입니다. */
    @Transactional(readOnly = true)
    public String answerContentOf(long questionId) {
        return answerRepository.findByQuestion_IdIn(List.of(questionId)).stream()
                .findFirst()
                .map(AiAnswer::getContent)
                .orElse(null);
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }
}
