package com.ajt.backend.domain.member;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 계정을 조회하는 저장소입니다.
 * 인증 기능에서는 이메일 기준 로그인, 중복 가입 확인, 비밀번호 재설정 대상 조회에 사용합니다.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);
}
