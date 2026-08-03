package com.ajt.backend.domain.department;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시스템 기본 부서('전체')를 보장하는 컴포넌트입니다(S15P11B106-146).
 *
 * <p>초기/더미 데이터 로딩 시 호출되어 기본 부서가 항상 존재하도록 만든다. 이미 있으면 중복 생성하지 않는다
 * (idempotent). ERD는 변경하지 않고, 데이터로만 기본 부서를 보장한다.
 */
@Component
public class DefaultDepartmentEnsurer {

    private static final Logger log = LoggerFactory.getLogger(DefaultDepartmentEnsurer.class);

    private final DepartmentRepository departmentRepository;

    public DefaultDepartmentEnsurer(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    /**
     * 기본 부서를 보장하고 그 부서를 반환합니다. 이미 있으면 기존 것을 그대로 반환합니다(중복 생성 없음).
     *
     * <p>예전 이름('전체')으로 남아 있으면 <b>새로 만들지 않고 개명한다</b>(S15P11B106-204).
     * 마이그레이션 도구가 없어 기동 때 여기서 처리한다. 부서 ID 는 그대로라 소속 사용자·일정·
     * Wiki 범위가 영향받지 않는다. 둘 다 있으면(수동으로 '미지정'을 만든 경우) 개명하지 않는다 —
     * 이름이 유니크 제약이라 충돌한다.
     */
    @Transactional
    public Department ensure() {
        var departments = departmentRepository.findAllByOrderByNameAsc();
        var current = departments.stream()
                .filter(department -> Department.DEFAULT_NAME.equals(department.getName()))
                .findFirst();
        if (current.isPresent()) {
            return current.get();
        }

        var legacy = departments.stream()
                .filter(department -> Department.LEGACY_DEFAULT_NAME.equals(department.getName()))
                .findFirst();
        if (legacy.isPresent()) {
            Department renamed = legacy.get();
            renamed.changeName(Department.DEFAULT_NAME);
            departmentRepository.save(renamed);
            log.info("시스템 기본 부서를 '{}'에서 '{}'로 개명했습니다.",
                    Department.LEGACY_DEFAULT_NAME, Department.DEFAULT_NAME);
            return renamed;
        }

        Department created = departmentRepository.save(new Department(Department.DEFAULT_NAME));
        log.info("시스템 기본 부서 '{}'를 생성했습니다.", Department.DEFAULT_NAME);
        return created;
    }
}
