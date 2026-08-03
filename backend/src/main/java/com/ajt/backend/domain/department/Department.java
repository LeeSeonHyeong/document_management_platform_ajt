package com.ajt.backend.domain.department;

import com.ajt.backend.domain.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.Getter;

/**
 * 회원, 문서 공개 범위, 일정 공개 범위에서 공통으로 사용하는 부서 엔티티입니다.
 * 부서 관리자는 부서 관리 API에서만 지정하거나 해제합니다.
 */
@Getter
@Entity
@Table(name = "department")
public class Department {

    /**
     * 시스템 기본 부서명입니다(S15P11B106-146). 항상 존재하며 이름 변경·삭제가 금지됩니다.
     *
     * <p>소속 부서를 아직 정하지 않은 사람이 들어가는 자리다. <b>공개 범위와는 아무 관계가 없다</b> —
     * 전사 공개는 {@code scope_key = "ALL"} 리터럴이고 부서 목록이 비어 있다({@code ScopeKey}).
     */
    public static final String DEFAULT_NAME = "미지정";

    /**
     * 예전 기본 부서명입니다(S15P11B106-204).
     *
     * <p>「전체」는 화면의 다른 두 「전체」와 충돌했다 — 일정 탭의 「전체 부서」(필터)와 공개 범위의
     * 「전체 공개」(권한)다. 부서 하나가 그 둘과 같은 이름이라, 「부서 공개 + 전체」를 고르면 전사
     * 공개인 줄 알기 쉬웠다. 실제로는 그 부서 소속자에게만 보인다.
     *
     * <p>마이그레이션 도구가 없어 {@code DefaultDepartmentEnsurer}가 기동 때 이 이름을 찾아
     * 개명한다. 부서 ID는 그대로라 소속 사용자·일정·Wiki 범위는 영향받지 않는다.
     */
    public static final String LEGACY_DEFAULT_NAME = "전체";

    /**
     * 명목상 부서명입니다(S15P11B106-183).
     *
     * <p>최고관리자 계정을 소속시키기 위한 부서로, DB에는 존재할 수 있으나 실제 조직 부서가 아니므로
     * 사용자 화면·API 응답에는 노출하지 않습니다. 부서 목록·회원가입 부서 목록에서 제외되고,
     * 사용자 응답에서는 소속 부서가 이 부서면 department를 null로 처리합니다.
     * 명목상 부서 판정은 이 상수와 {@link #isNominal()} 한 곳에서만 관리합니다.
     */
    public static final String NOMINAL_DEPARTMENT_NAME = "최고관리자";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "department_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Member manager;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    protected Department() {
    }

    public Department(String name) {
        this.name = requireName(name);
    }

    /**
     * DEPT-02/03 부서명 규칙입니다.
     * 공백 이름은 저장하지 않고, 앞뒤 공백은 잘라서 저장합니다.
     */
    public void changeName(String name) {
        this.name = requireName(name);
    }

    /** 시스템 기본 부서('전체') 여부입니다. 기본 부서는 이름 변경·삭제가 금지됩니다(S15P11B106-146). */
    public boolean isDefault() {
        return DEFAULT_NAME.equals(name);
    }

    /**
     * 명목상 부서('최고관리자') 여부입니다(S15P11B106-183).
     * 이 부서는 부서 목록·회원가입 부서 목록·사용자 응답에서 숨깁니다(사용자에게 노출하지 않음).
     * 명목상 부서 판정이 필요한 곳(목록 제외·응답 숨김)은 모두 이 메서드를 재사용합니다.
     */
    public boolean isNominal() {
        return NOMINAL_DEPARTMENT_NAME.equals(name);
    }

    /**
     * DEPT-02/03 부서 관리자 지정 규칙입니다.
     * 지정 가능한 회원인지는 서비스에서 확인하고, 엔티티는 연결만 맡습니다.
     */
    public void assignManager(Member manager) {
        this.manager = Objects.requireNonNull(manager, "부서 관리자는 null일 수 없습니다.");
    }

    /**
     * DEPT-03 부서 관리자 해제 규칙입니다.
     * 관리자 미지정 부서가 허용되므로 null로 비웁니다.
     */
    public void clearManager() {
        this.manager = null;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("부서명은 필수입니다.");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 50) {
            throw new IllegalArgumentException("부서명은 50자 이하여야 합니다.");
        }
        return trimmedName;
    }
}
