package com.ajt.backend.global.config;

import com.ajt.backend.domain.department.Department;
import com.ajt.backend.domain.department.DepartmentRepository;
import com.ajt.backend.domain.member.Member;
import com.ajt.backend.domain.member.MemberRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * local H2 테스트용 기본 데이터를 넣습니다.
 * 실제 배포 환경에서는 실행되지 않고, Postman으로 인증 API를 확인할 때만 사용합니다.
 */
@Configuration
@Profile("local")
@ConditionalOnProperty(prefix = "ajt.local-data", name = "enabled", havingValue = "true")
public class LocalDataInitializer {

    @Bean
    CommandLineRunner seedLocalData(
            DepartmentRepository departmentRepository,
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder
    ) {
        return args -> {
            Department department = findOrCreateDepartment(departmentRepository);
            createApprovedMemberIfAbsent(memberRepository, passwordEncoder, department);
        };
    }

    private Department findOrCreateDepartment(DepartmentRepository departmentRepository) {
        return departmentRepository.findAll()
                .stream()
                .findFirst()
                .orElseGet(() -> departmentRepository.save(new Department("개발부")));
    }

    private void createApprovedMemberIfAbsent(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            Department department
    ) {
        memberRepository.findByEmail("employee@ajt.com")
                .orElseGet(() -> memberRepository.save(Member.approvedEmployee(
                        department,
                        "employee@ajt.com",
                        "홍길동",
                        passwordEncoder.encode("password123!"),
                        "AJT-2026-0001"
                )));
    }
}
