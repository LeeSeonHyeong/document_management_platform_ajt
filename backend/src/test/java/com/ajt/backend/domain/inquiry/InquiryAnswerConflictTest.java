package com.ajt.backend.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ajt.backend.domain.inquiry.storage.InquiryFileStorage;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import com.ajt.backend.domain.member.Role;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 문의 답변 동시 등록 충돌 처리(S15P11B106-105).
 * 두 담당자가 거의 동시에 답변을 등록하면 나중 저장이 inquiry_id UNIQUE 제약에 걸린다. 이 DB 충돌을 실제
 * 동시성으로 결정적으로 재현하기 어렵기 때문에, 저장 후 flush가 DataIntegrityViolationException을 던지는
 * 상황을 목으로 재현해 서비스가 500이 아니라 409(INQUIRY_ANSWER_ALREADY_EXISTS)로 변환하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InquiryAnswerConflictTest {

    @Mock
    private InquiryRepository inquiryRepository;
    @Mock
    private InquiryReplyRepository inquiryReplyRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private InquiryFileStorage fileStorage;

    @InjectMocks
    private InquiryService inquiryService;

    @Test
    @DisplayName("답변 저장 중 inquiry_id UNIQUE 충돌(DataIntegrityViolation)이 나면 409로 변환한다")
    void upsertAnswerConvertsUniqueViolationToConflict() {
        long inquiryId = 55L;
        AuthenticatedMember assigneeActor = new AuthenticatedMember(7L, "admin@ajt.com", Role.ADMIN);

        Member assignee = mock(Member.class);
        when(assignee.getId()).thenReturn(7L);
        Inquiry inquiry = mock(Inquiry.class);
        when(inquiry.getAssignee()).thenReturn(assignee);

        when(inquiryRepository.findById(inquiryId)).thenReturn(Optional.of(inquiry));
        // 두 요청 모두 "답변 없음"으로 판단하는 상황: findByInquiryId가 비어 있다.
        when(inquiryReplyRepository.findByInquiryId(inquiryId)).thenReturn(Optional.empty());
        when(inquiryReplyRepository.save(any())).thenReturn(mock(InquiryReply.class));
        // 나중 요청의 INSERT가 UNIQUE 제약에 걸린 상황을 flush에서 재현한다.
        doThrow(new DataIntegrityViolationException("uk_inquiry_reply_inquiry"))
                .when(inquiryReplyRepository).flush();

        assertThatThrownBy(() -> inquiryService.upsertAnswer(assigneeActor, inquiryId, "연차는 규정에 따라 사용할 수 있습니다."))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INQUIRY_ANSWER_ALREADY_EXISTS);
    }
}
