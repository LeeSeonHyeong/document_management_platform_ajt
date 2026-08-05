package com.ajt.backend.domain.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wiki 관계 대칭 보정")
class WikiRelationRepairServiceTest {

    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final WikiRelationRepairService service =
            new WikiRelationRepairService(wikiRepository);

    @Test
    @DisplayName("한쪽에만 있는 참조를 반대쪽에 채운다")
    void fillsTheMissingSide() throws Exception {
        Wiki a = wiki(101L, "ALL");
        Wiki b = wiki(108L, "ALL");
        a.addWikiRef(108L);
        given(wikiRepository.findAll()).willReturn(List.of(a, b));

        int filled = service.repairAsymmetricRelations();

        assertThat(filled).isEqualTo(1);
        assertThat(b.wikiRefs()).containsExactly(101L);
    }

    @Test
    @DisplayName("이미 대칭인 관계는 건드리지 않는다")
    void leavesSymmetricRelationsAlone() throws Exception {
        Wiki a = wiki(101L, "ALL");
        Wiki b = wiki(108L, "ALL");
        a.addWikiRef(108L);
        b.addWikiRef(101L);
        given(wikiRepository.findAll()).willReturn(List.of(a, b));

        assertThat(service.repairAsymmetricRelations()).isZero();
        assertThat(a.wikiRefs()).containsExactly(108L);
        assertThat(b.wikiRefs()).containsExactly(101L);
    }

    @Test
    @DisplayName("두 번 돌려도 결과가 같다")
    void isIdempotent() throws Exception {
        Wiki a = wiki(101L, "ALL");
        Wiki b = wiki(108L, "ALL");
        a.addWikiRef(108L);
        given(wikiRepository.findAll()).willReturn(List.of(a, b));

        service.repairAsymmetricRelations();

        assertThat(service.repairAsymmetricRelations()).isZero();
        assertThat(b.wikiRefs()).containsExactly(101L);
    }

    @Test
    @DisplayName("다른 공간의 Wiki 를 가리키는 참조는 채우지 않는다")
    void doesNotCrossScopes() throws Exception {
        Wiki a = wiki(101L, "ALL");
        Wiki other = wiki(200L, "D1");
        a.addWikiRef(200L);
        given(wikiRepository.findAll()).willReturn(List.of(a, other));

        assertThat(service.repairAsymmetricRelations()).isZero();
        assertThat(other.wikiRefs()).isEmpty();
    }

    @Test
    @DisplayName("사라진 Wiki 를 가리키는 참조는 건너뛴다")
    void skipsMissingTargets() throws Exception {
        Wiki a = wiki(101L, "ALL");
        a.addWikiRef(999L);
        given(wikiRepository.findAll()).willReturn(List.of(a));

        assertThat(service.repairAsymmetricRelations()).isZero();
        assertThat(a.wikiRefs()).containsExactly(999L);
    }

    private static Wiki wiki(long id, String scopeKey) throws Exception {
        Wiki wiki = Wiki.create(scopeKey, 10L, "제목 " + id);
        Field field = Wiki.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(wiki, id);
        return wiki;
    }
}
