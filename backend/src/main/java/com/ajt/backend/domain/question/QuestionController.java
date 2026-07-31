package com.ajt.backend.domain.question;

import com.ajt.backend.domain.question.dto.QuestionAskRequest;
import com.ajt.backend.domain.question.dto.QuestionAskResponse;
import com.ajt.backend.domain.question.dto.QuestionHistoryResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 질문 이력 API입니다.
 * 챗봇 질문 이력 화면에서 로그인 사용자 본인의 질문·답변·출처를 조회합니다(FR-QNA-008).
 */
@RestController
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionAskService questionAskService;

    public QuestionController(QuestionService questionService, QuestionAskService questionAskService) {
        this.questionService = questionService;
        this.questionAskService = questionAskService;
    }

    /**
     * POST /api/v1/questions
     * 접근 가능한 Wiki·일정을 대상으로 AI에게 질문합니다. 답변과 출처를 함께 반환합니다.
     */
    @PostMapping("/api/v1/questions")
    public QuestionAskResponse ask(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestBody QuestionAskRequest request
    ) {
        return questionAskService.ask(loginMember, request);
    }

    /**
     * GET /api/v1/questions
     * 로그인 사용자 본인의 질문 이력을 조회합니다. conversationId·questionType으로 필터링할 수 있습니다.
     */
    @GetMapping("/api/v1/questions")
    public QuestionHistoryResponse questions(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(name = "conversationId", required = false) String conversationId,
            @RequestParam(name = "questionType", required = false) String questionType,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return questionService.findMyQuestions(loginMember, conversationId, questionType, page, size);
    }
}
