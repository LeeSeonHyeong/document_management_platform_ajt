package com.ajt.backend.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.inquiry.InquiryRepository;
import com.ajt.backend.domain.member.dto.SignupApprovalResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import com.ajt.backend.global.error.BusinessException;
import com.ajt.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 가입 승인 시 사번(employeeNo) UNIQUE 충돌에 대한 재시도 동작을 검증한다(S15P11B106-72).
 * 동시성 충돌은 실제 DB에서 결정적으로 재현하기 어려우므로, 저장(flush)이 충돌을 던지는 상황을 목으로 재현한다.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceApprovalRetryTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private InquiryRepository inquiryRepository;
    @Mock
    private ObjectProvider<MemberService> selfProvider;

    private MemberService memberService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC);
        memberService = new MemberService(
                memberRepository, departmentRepository, inquiryRepository, clock, selfProvider);
        // self 프록시 호출이 실제 인스턴스 메서드를 그대로 실행하도록 한다(트랜잭션 경계는 이 단위테스트의 관심사가 아님).
        when(selfProvider.getObject()).thenReturn(memberService);
    }

    @Test
    @DisplayName("사번 저장이 UNIQUE 충돌로 실패하면 다음 번호로 재시도해 승인에 성공한다")
    void retriesOnEmployeeNoConflictThenSucceeds() {
        // 실패한 시도는 롤백 후 회원을 다시 읽으므로, 시도마다 깨끗한(PENDING) 회원을 돌려준다.
        when(memberRepository.findById(1L)).thenAnswer(invocation -> Optional.of(newPending()));
        when(memberRepository.existsByEmployeeNo(anyString())).thenReturn(false);
        // 첫 flush는 사번 충돌, 두 번째 flush는 성공한다.
        doThrow(new DataIntegrityViolationException("employee_no unique"))
                .doNothing()
                .when(memberRepository).flush();

        SignupApprovalResponse response = memberService.approveSignupRequest(admin(), 1L);

        assertThat(response.employeeNo()).startsWith("AJT-");
        assertThat(response.signupStatus()).isEqualTo("approved");
        verify(memberRepository, times(2)).flush();
    }

    @Test
    @DisplayName("사번 충돌이 최대 시도 횟수만큼 계속되면 409로 실패한다")
    void failsWithConflictAfterMaxAttempts() {
        when(memberRepository.findById(1L)).thenAnswer(invocation -> Optional.of(newPending()));
        when(memberRepository.existsByEmployeeNo(anyString())).thenReturn(false);
        doThrow(new DataIntegrityViolationException("employee_no unique"))
                .when(memberRepository).flush();

        assertThatThrownBy(() -> memberService.approveSignupRequest(admin(), 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        // 최대 시도 횟수(5)만큼 저장을 시도했는지 확인한다.
        verify(memberRepository, times(5)).flush();
    }

    private Member newPending() {
        return Member.signup(new Department("개발부"), "pending@ajt.com", "신청자", "hash");
    }

    private AuthenticatedMember admin() {
        return new AuthenticatedMember(99L, "admin@ajt.com", Role.ADMIN);
    }
}
