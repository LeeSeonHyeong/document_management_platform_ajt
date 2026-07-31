package com.ajt.backend.domain.question;

import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.question.QuestionAskService.VerifiedSources;
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
     * <p>출처는 <b>권한 검증을 통과한 자료만</b> 저장한다. AI 응답에 그 밖의 ID가 있어도 무시하며,
     * 제목도 AI가 준 값이 아니라 DB 값을 쓴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuestionAskResponse saveAnswer(
            long questionId,
            String conversationKey,
            String questionType,
            AnswerGenerationResponse answer,
            VerifiedSources verified,
            Function<List<Long>, List<QuestionEvidenceDocumentResponse>> evidenceLoader
    ) {
        AiQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalStateException("질문을 찾을 수 없습니다: " + questionId));
        question.recordSuccess(QuestionType.fromApiValue(questionType));

        AiAnswer saved = answerRepository.save(AiAnswer.create(question, answer.answer()));

        List<QuestionAskSourceResponse> sources = new ArrayList<>();
        for (AnswerGenerationResponse.Source source : answer.sources()) {
            if (source.isWiki()) {
                Long wikiId = parseId(source.wikiId());
                String title = wikiId == null ? null : verified.wikiTitles().get(wikiId);
                if (title == null) {
                    // 권한 검증을 통과하지 않은 출처다. 저장·응답에서 제외한다.
                    continue;
                }
                answerSourceRepository.save(AnswerSource.wiki(saved, wikiId, title));
                sources.add(QuestionAskSourceResponse.wiki(
                        String.valueOf(wikiId),
                        title,
                        evidenceLoader.apply(verified.wikiDocumentRefs().getOrDefault(wikiId, List.of()))
                ));
                continue;
            }
            if (source.isSchedule()) {
                Long scheduleId = parseId(source.scheduleId());
                String title = scheduleId == null ? null : verified.scheduleTitles().get(scheduleId);
                if (title == null) {
                    continue;
                }
                answerSourceRepository.save(AnswerSource.schedule(saved, scheduleId, title));
                sources.add(QuestionAskSourceResponse.schedule(String.valueOf(scheduleId), title));
            }
        }

        return new QuestionAskResponse(
                conversationKey,
                String.valueOf(questionId),
                questionType,
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
