# Wiki 간 관계를 무방향으로 저장한다 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wiki A 가 Wiki B 를 링크하면 **양쪽 상세 화면에서 서로가 보이게** 한다 (FR-WIKI-010·DR-003).

**Architecture:** 세 겹으로 고친다. ① 저장 — `applyRelationChanges` 가 한 트랜잭션에서 양쪽 `wiki_refs` JSON 을 바꾼다. ② 보정 — 기동 시 한 번 기존 반쪽 관계를 대칭으로 만든다 (idempotent `ApplicationRunner`). ③ 읽기 — 상세 조회가 역방향도 합쳐 읽어, 보정 전에도 화면이 맞고 이후 어떤 경로로 반쪽이 생겨도 화면은 옳다. **AI 서버·ERD·계약·프론트는 바꾸지 않는다.**

**Tech Stack:** Java 21, Spring Boot, Gradle, JUnit 5 + AssertJ + Mockito(BDD 스타일), MySQL 8.4 (`wiki.wiki_refs` JSON 컬럼)

## Global Constraints

- **ERD 무변경.** `wiki.wiki_refs` JSON 컬럼을 그대로 쓴다. 새 컬럼·표·제약을 만들지 않는다. 스키마와 코드가 어긋나면 ERD 를 정답으로 본다
- **AI 서버 무변경.** AI 는 본문 링크 한 방향만 보낸다 — 본문이 그렇기 때문이다. 「무방향으로 만드는 것」은 계약상 백엔드 책임이다
- **계약 무변경.** `relationChanges[]` 의 모양(`action`·`type`·`sourceWikiRef`·`targetWikiRef`)은 그대로다
- **프론트 무변경.** 응답 필드 `relatedWikis` 의 모양이 바뀌지 않는다 — 항목이 늘어날 뿐이다
- **커밋 규칙**: `../docs/conventions/git-convention.md`. 타입은 영어 소문자, 설명은 한국어, 마침표 없음, **커밋 메시지에 Jira 키를 쓰지 않는다**. 한 커밋에 하나의 목적
- **stage 경로를 하나하나 명시한다.** `git add -A`, `git add .` 금지
- **백엔드 변경이므로 기준선을 먼저 잡는다**: `sh gradlew test` 를 착수 전에 돌려 기존 실패 목록을 기록한다. 내가 늘린 실패가 있는지 그 차이로만 판단한다
- **자기 참조 금지·타 scope 침범 금지는 기존 검증을 유지한다** (`Wiki#addWikiRef` 의 `IllegalArgumentException`, `findWiki` 의 scope 필터)
- 브랜치: `fix/S15P11B106-<티켓번호>-undirected-wiki-relations` (착수 전 티켓을 백로그에서 먼저 찾고, 없으면 만든다)

## 관련 티켓

- **S15P11B106-83** — 완료 조건에 "relatedWikis 무방향 관계 표시"가 있었으나 저장이 한쪽만이라 충족되지 않았다. 완료 처리된 티켓이므로 **갈아 쓰지 말고 참조만 한다**
- **S15P11B106-256** (미착수) — 같은 `applyRelationChanges` 를 건드린다. 먼저 착수됐는지 확인하고, 겹치면 충돌을 조정한다
- 설계: `ai/docs/superpowers/specs/2026-08-05-derived-data-ownership-design.md` §3②

---

## File Structure

| 파일 | 책임 | 변경 |
| --- | --- | --- |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java` | 변환 결과 반영. 관계 변경을 양쪽에 쓴다 | 수정 (Task 1) |
| `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java` | 위 검증. **기존 단정 하나가 한쪽만 저장을 고정하고 있어 함께 고친다** | 수정 (Task 1) |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairService.java` | 기존 반쪽 관계를 대칭으로 만드는 보정. 한 공간씩 처리 | **신규** (Task 2) |
| `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairServiceTest.java` | 위 검증 | **신규** (Task 2) |
| `backend/src/main/java/com/ajt/backend/global/config/WikiRelationIntegrityConfig.java` | 기동 시 보정을 한 번 실행하는 `ApplicationRunner` | **신규** (Task 3) |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java` | Wiki 상세 조립. 역방향을 합쳐 읽는다 | 수정 (Task 4) |
| `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiChatMessageService.java` | 채팅 응답의 Wiki 상세. 같은 조립 | 수정 (Task 4) |
| `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiQueryServiceTest.java` | 위 검증 | 수정 (Task 4) |

**왜 보정을 별 클래스로 두나.** `WikiTransformationApplier` 는 「AI 응답 1건 반영」이 책임이다. 전 공간 일괄 보정은 다른 생애(기동 시 1회)와 다른 입력(응답 없음)을 갖는다. 같은 클래스에 넣으면 반영 경로가 부팅 관심사를 안게 된다.

---

## Task 0: 기준선

**Files:** 없음 (측정만)

**Interfaces:**
- Consumes: 없음
- Produces: 기존 테스트 실패 목록 (이후 태스크가 "내가 늘린 실패"를 판단하는 기준)

- [ ] **Step 1: 백엔드 전체 테스트를 돌려 기준선을 기록한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

실패가 있으면 그 목록을 그대로 적어둔다. **0건이 아니어도 진행한다** — 판단 기준은 "내가 늘렸는지"뿐이다.

- [ ] **Step 2: 티켓을 확보한다**

Jira 프로젝트 `S15P11B106` 백로그에서 무방향 관계 관련 티켓을 먼저 찾는다. 없으면 버그 유형으로 새로 만든다. **백엔드 티켓은 버려진 티켓을 가져와 갈아 쓰지 않는다.**

- [ ] **Step 3: 브랜치를 만든다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git fetch origin
git switch -c fix/S15P11B106-<티켓번호>-undirected-wiki-relations origin/develop
```

---

## Task 1: 관계 변경을 양쪽에 저장한다

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java:321-343`
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java:241` (기존 단정 수정), 파일 끝에 테스트 3개 추가

**Interfaces:**
- Consumes: `Wiki#addWikiRef(long)`·`Wiki#removeWikiRef(long)` (기존), `findWiki(String scopeKey, long wikiId) -> Wiki` (기존 private, scope 불일치면 `IllegalArgumentException`)
- Produces: `private void linkWikis(Wiki source, Wiki target)` 와 `private void unlinkWikis(Wiki source, Wiki target)` — 같은 클래스 안에서만 쓴다. 호출자가 짝을 기억하지 않아도 되게 하는 것이 목적이다

- [ ] **Step 1: 기존 단정이 한쪽만 저장을 고정하고 있음을 확인한다**

`WikiTransformationApplierTest.java` 의 `appliesUpdateAndRelation()` 안에 이 줄이 있다:

```java
assertThat(related.wikiRefs()).isEmpty();
```

`related`(108) 는 `RelationChange("add", "101", "108")` 의 **대상**이다. 이 단정이 「대상에는 쓰지 않는다」를 고정한다. **이 줄이 우리가 바꿀 동작이다.**

- [ ] **Step 2: 실패하는 테스트 세 개를 쓴다**

`WikiTransformationApplierTest.java` 끝(마지막 `}` 앞)에 추가한다. 기존 픽스처(`SCOPE_KEY`·`DOCUMENT_ID`·`applier`·`existingWiki`)를 그대로 쓴다.

```java
    @Test
    @DisplayName("관계 추가를 양쪽 Wiki 에 저장한다")
    void addsRelationToBothWikis() {
        Wiki source = existingWiki(101L, 10L, "휴가 규정");
        Wiki target = existingWiki(108L, 10L, "근태 관리");

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(),
                List.of(new RelationChange("add", "101", "108")),
                List.of()));

        assertThat(source.wikiRefs()).containsExactly(108L);
        assertThat(target.wikiRefs()).containsExactly(101L);
    }

    @Test
    @DisplayName("관계 삭제를 양쪽 Wiki 에서 지운다")
    void removesRelationFromBothWikis() {
        Wiki source = existingWiki(101L, 10L, "휴가 규정");
        Wiki target = existingWiki(108L, 10L, "근태 관리");
        source.addWikiRef(108L);
        target.addWikiRef(101L);

        applier.apply(SCOPE_KEY, DOCUMENT_ID, new WikiTransformationResponse(
                "요약", List.of(), List.of(),
                List.of(new RelationChange("remove", "101", "108")),
                List.of()));

        assertThat(source.wikiRefs()).isEmpty();
        assertThat(target.wikiRefs()).isEmpty();
    }

    @Test
    @DisplayName("자기 자신을 가리키는 관계는 양쪽 저장에서도 거부한다")
    void rejectsSelfRelation() {
        existingWiki(101L, 10L, "휴가 규정");

        assertThatThrownBy(() -> applier.apply(SCOPE_KEY, DOCUMENT_ID,
                new WikiTransformationResponse(
                        "요약", List.of(), List.of(),
                        List.of(new RelationChange("add", "101", "101")),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신");
    }
```

- [ ] **Step 3: 기존 단정을 바꾼다**

`appliesUpdateAndRelation()` 의 그 줄을 이렇게 바꾼다. **삭제하지 말고 반대 단정으로 바꾼다** — 양쪽 저장을 그 테스트도 함께 잠근다.

```java
        assertThat(related.wikiRefs()).containsExactly(101L);
```

- [ ] **Step 4: 테스트를 돌려 실패를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiTransformationApplierTest*'
```

Expected: `addsRelationToBothWikis` 는 `target.wikiRefs()` 가 비어 FAIL, `removesRelationFromBothWikis` 는 `target.wikiRefs()` 에 101 이 남아 FAIL, `appliesUpdateAndRelation` 은 바꾼 단정 때문에 FAIL. `rejectsSelfRelation` 은 **이미 통과할 수도 있다** (`source.addWikiRef(101)` 이 던진다) — 통과해도 그대로 둔다. 양쪽 저장으로 바꾼 뒤에도 거부가 유지되는지 잠그는 회귀 테스트다.

- [ ] **Step 5: `applyRelationChanges` 를 고친다**

`WikiTransformationApplier.java` 의 `applyRelationChanges` 본문을 바꾼다. `requireWikiInScope(scopeKey, targetWikiId)` 를 `findWiki` 로 바꿔 **대상 엔티티를 실제로 가져온다.**

```java
        for (RelationChange change : changes) {
            long sourceWikiId = resolveWikiRef(change.sourceWikiRef(), wikiIdsByRef);
            long targetWikiId = resolveWikiRef(change.targetWikiRef(), wikiIdsByRef);
            if (deletedWikiIds.contains(sourceWikiId) || deletedWikiIds.contains(targetWikiId)) {
                continue;
            }
            Wiki source = findWiki(scopeKey, sourceWikiId);
            Wiki target = findWiki(scopeKey, targetWikiId);
            switch (action(change.action())) {
                case ACTION_ADD -> linkWikis(source, target);
                case ACTION_REMOVE -> unlinkWikis(source, target);
                default -> throw new IllegalArgumentException(
                        "지원하지 않는 Wiki 관계 동작입니다: " + change.action()
                );
            }
        }
```

같은 클래스에 헬퍼 두 개를 추가한다. `applyRelationChanges` 바로 아래에 둔다.

```java
    /**
     * Wiki-Wiki 관계는 무방향입니다 (FR-WIKI-010). 양쪽 JSON 을 함께 바꿔야 하므로
     * (DR-003) 호출자가 짝을 기억하지 않도록 여기서 묶습니다.
     *
     * <p>앞 판본은 {@code source.addWikiRef(target)} 만 불렀습니다. 한쪽만 저장되어
     * 반대쪽 상세 화면에서 관계가 보이지 않았고, 두 페이지가 서로 링크한 경우에만
     * 정상처럼 보여 간헐적 결함으로 나타났습니다.
     */
    private void linkWikis(Wiki source, Wiki target) {
        source.addWikiRef(target.id());
        target.addWikiRef(source.id());
    }

    private void unlinkWikis(Wiki source, Wiki target) {
        source.removeWikiRef(target.id());
        target.removeWikiRef(source.id());
    }
```

`requireWikiInScope` 가 이 변경으로 쓰이지 않게 되면 **삭제한다** — 죽은 코드를 남기지 않는다. 다른 호출처가 있으면 남긴다:

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
grep -rn "requireWikiInScope" src/main/java
```

또한 클래스 docstring 격 주석(`applyRelationChanges` 위 Javadoc)에 「삭제되지 않은 Wiki 를 가리키는 잘못된 참조는 여전히 오류로 남긴다」가 있다. `findWiki` 가 그 검증을 그대로 하므로 **그 문장은 유지한다.**

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiTransformationApplierTest*'
```

Expected: 전부 PASS.

- [ ] **Step 7: 백엔드 전체 테스트로 회귀를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

Expected: Task 0 의 기준선과 비교해 **새로 실패한 것이 없다.** 실패가 늘었으면 그 테스트가 한쪽만 저장을 전제하고 있는지 확인하고, 그렇다면 Step 3 처럼 반대 단정으로 바꾼다.

- [ ] **Step 8: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplier.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiTransformationApplierTest.java
git commit -m "$(cat <<'EOF'
fix(wiki): Wiki 간 관계를 양쪽 JSON 에 저장한다

source 에만 쓰고 target 에는 쓰지 않아, A 상세에는 B 가 뜨는데 B 상세는 비어 있었다.
두 페이지가 서로 링크한 경우에만 정상처럼 보여 간헐적 결함으로 나타났다.

FR-WIKI-010 은 「한쪽에서 연결하면 양쪽 모두에서 연결로 조회되어야 한다」, DR-003 은
「양쪽 Wiki JSON 을 하나의 트랜잭션으로 함께 변경」을 요구한다.

호출자가 짝을 기억해야 하는 모양을 없앤다 — 지금 결함이 정확히 그것을 잊은 것이다.
EOF
)"
```

---

## Task 2: 기존 반쪽 관계를 대칭으로 만드는 보정

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairService.java`
- Create: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairServiceTest.java`

**Interfaces:**
- Consumes: `WikiRepository#findAll()`, `Wiki#id()`·`Wiki#scopeKey()`·`Wiki#wikiRefs()`·`Wiki#addWikiRef(long)`
- Produces: `public int repairAsymmetricRelations()` — 채운 참조 **건수**를 돌려준다. Task 3 이 로그에 쓴다

**왜 필요한가.** Task 1 은 앞으로 들어오는 관계만 고친다. **이미 반쪽으로 쌓인 것은 그대로 남는다** — QA 에서 본 위키들이 그렇다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairServiceTest.java` 를 새로 만든다.

```java
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
```

> `wiki(...)` 헬퍼가 리플렉션으로 id 를 넣는 것은 기존 `WikiTransformationApplierTest#assignId` 와 같은 방식이다 — `wiki_id` 는 DB 가 발급하므로 단위 테스트에서 다른 방법이 없다. `Wiki.create` 의 시그니처가 다르면 `WikiTransformationApplierTest` 의 `existingWiki` 구현을 그대로 따른다.

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiRelationRepairServiceTest*'
```

Expected: 컴파일 실패 — `WikiRelationRepairService` 가 없다.

- [ ] **Step 3: 서비스를 구현한다**

`backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairService.java` 를 새로 만든다.

```java
package com.ajt.backend.domain.wiki.service;

import com.ajt.backend.domain.wiki.model.Wiki;
import com.ajt.backend.domain.wiki.repository.WikiRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한쪽에만 저장된 Wiki-Wiki 참조를 대칭으로 만듭니다.
 *
 * <p>Wiki 관계는 무방향이고(FR-WIKI-010) 양쪽 JSON 에 저장해야 하는데(DR-003), 반영 경로가
 * source 에만 쓰던 기간이 있었습니다. 그 기간에 쌓인 반쪽 관계는 저장을 고쳐도 스스로 낫지
 * 않으므로 한 번 채워야 합니다.
 *
 * <p><b>이미 있는 참조만 대칭으로 만듭니다 — 새 관계를 만들지 않습니다.</b> 그래서 몇 번
 * 돌려도 결과가 같고(idempotent), 기동마다 실행해도 안전합니다.
 */
@Service
public class WikiRelationRepairService {

    private final WikiRepository wikiRepository;

    public WikiRelationRepairService(WikiRepository wikiRepository) {
        this.wikiRepository = wikiRepository;
    }

    /**
     * @return 반대쪽에 채운 참조 건수
     */
    @Transactional
    public int repairAsymmetricRelations() {
        Map<Long, Wiki> wikisById = new LinkedHashMap<>();
        for (Wiki wiki : wikiRepository.findAll()) {
            wikisById.put(wiki.id(), wiki);
        }

        int filled = 0;
        for (Wiki source : wikisById.values()) {
            for (Long targetId : source.wikiRefs()) {
                Wiki target = wikisById.get(targetId);
                // 사라진 Wiki 를 가리키는 참조는 이 보정의 대상이 아니다.
                // 다른 공간 침범도 채우지 않는다 — 범위를 넘는 관계는 애초에 무효다.
                if (target == null || !target.scopeKey().equals(source.scopeKey())) {
                    continue;
                }
                if (target.wikiRefs().contains(source.id())) {
                    continue;
                }
                target.addWikiRef(source.id());
                filled++;
            }
        }
        return filled;
    }
}
```

> `Wiki#scopeKey()` 접근자 이름이 다르면 `Wiki.java` 를 열어 실제 이름을 쓴다. `belongsToScope(String)` 만 있으면 `!target.belongsToScope(source.scopeKey())` 로 바꾼다.

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiRelationRepairServiceTest*'
```

Expected: 5개 PASS.

- [ ] **Step 5: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairService.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiRelationRepairServiceTest.java
git commit -m "$(cat <<'EOF'
feat(wiki): 반쪽으로 저장된 Wiki 관계를 대칭으로 채우는 보정을 추가한다

반영 경로가 source 에만 쓰던 기간에 쌓인 반쪽 관계는 저장을 고쳐도 스스로 낫지 않는다.

이미 있는 참조만 대칭으로 만들고 새 관계는 만들지 않으므로 몇 번 돌려도 결과가 같다.
사라진 Wiki 를 가리키는 참조와 공간을 넘는 참조는 채우지 않는다.
EOF
)"
```

---

## Task 3: 기동 시 보정을 한 번 실행한다

**Files:**
- Create: `backend/src/main/java/com/ajt/backend/global/config/WikiRelationIntegrityConfig.java`

**Interfaces:**
- Consumes: `WikiRelationRepairService#repairAsymmetricRelations() -> int` (Task 2)
- Produces: 없음 (부팅 훅)

**패턴 근거.** 같은 저장소에 선례가 있다 — `global/config/DepartmentManagerIntegrityConfig.java` 가 기동 시 부서장 지정 정합성을 idempotent 하게 한 번 점검한다. **그 파일을 먼저 읽고 같은 모양으로 쓴다.**

- [ ] **Step 1: 선례를 읽는다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
cat src/main/java/com/ajt/backend/global/config/DepartmentManagerIntegrityConfig.java
```

- [ ] **Step 2: `ApplicationRunner` 를 만든다**

`backend/src/main/java/com/ajt/backend/global/config/WikiRelationIntegrityConfig.java`:

```java
package com.ajt.backend.global.config;

import com.ajt.backend.domain.wiki.service.WikiRelationRepairService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 시작 시 Wiki-Wiki 관계의 대칭성을 한 번 점검·보정합니다.
 *
 * <p>관계를 양쪽 JSON 에 저장하도록 고치기 전(FR-WIKI-010·DR-003)에 쌓인 반쪽 관계를 채웁니다.
 * 이미 있는 참조만 대칭으로 만들므로 매 기동 시 실행해도 안전하고(idempotent), 보정이 끝난
 * 뒤에는 대상이 없어 실질적인 no-op 이 됩니다.
 */
@Configuration
public class WikiRelationIntegrityConfig {

    private static final Logger log = LoggerFactory.getLogger(WikiRelationIntegrityConfig.class);

    @Bean
    ApplicationRunner repairAsymmetricWikiRelations(WikiRelationRepairService repairService) {
        return args -> {
            int filled = repairService.repairAsymmetricRelations();
            if (filled > 0) {
                log.warn("한쪽에만 저장돼 있던 Wiki 관계 {}건을 대칭으로 채웠습니다.", filled);
            } else {
                log.info("Wiki 관계 대칭성 점검 완료: 보정 대상 없음");
            }
        };
    }
}
```

- [ ] **Step 3: 애플리케이션이 뜨는 것을 확인한다**

기존 컨텍스트 로딩 테스트가 이 빈까지 함께 검증한다.

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

Expected: Task 0 기준선 대비 새 실패 없음. 컨텍스트 로딩 테스트가 통과하면 빈 배선이 맞다.

- [ ] **Step 4: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add backend/src/main/java/com/ajt/backend/global/config/WikiRelationIntegrityConfig.java
git commit -m "$(cat <<'EOF'
feat(wiki): 기동 시 Wiki 관계 대칭성을 한 번 점검한다

DepartmentManagerIntegrityConfig 와 같은 방식이다. 이미 있는 참조만 대칭으로 만들므로
매 기동 시 실행해도 안전하고, 보정이 끝난 뒤에는 대상이 없어 no-op 이 된다.
EOF
)"
```

---

## Task 4: 상세 조회가 역방향도 합쳐 읽는다

**Files:**
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java:459-478` (`relatedWikis`)
- Modify: `backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiChatMessageService.java:252-270` (`relatedWikis`)
- Modify: `backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiReadQueryServiceTest.java` (테스트 2개 추가)

**Interfaces:**
- Consumes: `WikiRepository#findAllByScopeKey(String) -> List<Wiki>` (기존), `Wiki#wikiRefs() -> List<Long>`
- Produces: 없음 (내부 조립만 바뀐다). 응답 타입 `WikiDetailResponse.RelatedWiki(String wikiId, String title)` 는 그대로다

**왜 저장을 고쳤는데도 읽기를 고치나.** 보정 전에도 화면이 맞아야 하고, 이후 어떤 경로로 반쪽이 생겨도 화면은 옳아야 한다. `InternalWikiQueryService#relations` 가 이미 같은 방식으로 역방향을 계산한다 — 그쪽이 온전한 관계를 보고 있어서 데이터가 반쪽인 것이 오래 드러나지 않았다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`WikiReadQueryServiceTest.java` 에 추가한다. 이 파일은 `@SpringBootTest` + `@Transactional` 이고 실제 리포지터리를 쓴다. 기존 헬퍼 `saveWiki(scopeKey, categoryId, title)`·`authenticate(member)`·`employee(dept, email, name, empNo)` 를 그대로 쓴다 (`getWikiReturnsDetail()` 이 그 패턴이다).

```java
    @Test
    @DisplayName("Wiki 상세는 나를 가리키는 Wiki 도 관련 위키로 함께 보여준다")
    void getWikiIncludesWikisThatPointAtThisOne() {
        // 저장이 반쪽이던 기간에 쌓인 데이터: A 만 B 를 가리킨다.
        // 관계는 무방향이므로(FR-WIKI-010) B 상세를 열어도 A 가 보여야 한다.
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        wikiScopeRepository.save(WikiScope.all());
        WikiCategory category = wikiCategoryRepository.save(WikiCategory.create("ALL", "휴가 및 근태", null));
        Wiki a = saveWiki("ALL", category.id(), "휴가 규정");
        Wiki b = saveWiki("ALL", category.id(), "근태 관리");
        a.addWikiRef(b.id());
        wikiRepository.save(a);
        authenticate(employee);

        WikiDetailResponse response = wikiQueryService.getWiki(b.id());

        assertThat(response.relatedWikis())
                .extracting(WikiDetailResponse.RelatedWiki::wikiId)
                .containsExactly(String.valueOf(a.id()));
    }

    @Test
    @DisplayName("Wiki 상세는 양쪽에 저장된 관계를 두 번 보여주지 않는다")
    void getWikiDoesNotDuplicateSymmetricRelations() {
        Department dev = departmentRepository.save(new Department("개발부"));
        Member employee = memberRepository.save(employee(dev, "emp@ajt.com", "홍길동", "AJT-2026-0001"));
        wikiScopeRepository.save(WikiScope.all());
        WikiCategory category = wikiCategoryRepository.save(WikiCategory.create("ALL", "휴가 및 근태", null));
        Wiki a = saveWiki("ALL", category.id(), "휴가 규정");
        Wiki b = saveWiki("ALL", category.id(), "근태 관리");
        a.addWikiRef(b.id());
        b.addWikiRef(a.id());
        wikiRepository.save(a);
        wikiRepository.save(b);
        authenticate(employee);

        WikiDetailResponse response = wikiQueryService.getWiki(b.id());

        assertThat(response.relatedWikis())
                .extracting(WikiDetailResponse.RelatedWiki::wikiId)
                .containsExactly(String.valueOf(a.id()));
    }
```

`WikiDetailResponse` 는 이미 import 되어 있다. `RelatedWiki` 의 접근자 이름이 `wikiId()` 가 아니면 `domain/wiki/api/WikiDetailResponse.java` 를 열어 실제 이름을 쓴다.

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiReadQueryServiceTest*'
```

Expected: `getWikiIncludesWikisThatPointAtThisOne` FAIL (`relatedWikis` 가 비어 있다). `getWikiDoesNotDuplicateSymmetricRelations` 는 이미 PASS 다 — 중복 방지가 새 구현에서도 유지되는지 잠그는 회귀 테스트다.

- [ ] **Step 3: `WikiQueryService#relatedWikis` 를 고친다**

기존 시그니처 `relatedWikis(String scopeKey, List<Long> wikiRefs)` 를 유지하고, 안에서 역방향을 합친다. 호출부(`toDetail`)는 바꾸지 않는다 — 대신 **자기 id 를 알아야** 하므로 인자를 하나 늘린다.

`toDetail` 의 호출을 이렇게 바꾼다:

```java
                relatedWikis(wiki.scopeKey(), wiki.id(), wiki.wikiRefs()),
```

메서드를 이렇게 바꾼다:

```java
    /**
     * 관련 Wiki 는 무방향입니다 (FR-WIKI-010). 내 목록에 있는 것과 <b>나를 가리키는 것</b>을
     * 합쳐 돌려줍니다.
     *
     * <p>저장이 양쪽 JSON 을 함께 바꾸므로(DR-003) 원칙적으로는 내 목록만 읽어도 충분합니다.
     * 역방향까지 읽는 이유는 두 가지입니다 — 반영 경로가 source 에만 쓰던 기간에 쌓인 반쪽
     * 관계가 보정 전에도 화면에 맞게 보여야 하고, 이후 어떤 경로로 반쪽이 생겨도 화면은
     * 옳아야 합니다. {@code InternalWikiQueryService#relations} 가 이미 같은 방식입니다.
     */
    private List<WikiDetailResponse.RelatedWiki> relatedWikis(
            String scopeKey, long wikiId, List<Long> wikiRefs) {
        Map<Long, Wiki> relatedById = new LinkedHashMap<>();
        if (!wikiRefs.isEmpty()) {
            wikiRepository.findAllByScopeKeyAndIdIn(scopeKey, wikiRefs)
                    .forEach(related -> relatedById.put(related.id(), related));
        }
        for (Wiki candidate : wikiRepository.findAllByScopeKey(scopeKey)) {
            if (candidate.id() != wikiId && candidate.wikiRefs().contains(wikiId)) {
                relatedById.putIfAbsent(candidate.id(), candidate);
            }
        }
        return relatedById.values().stream()
                .map(related -> new WikiDetailResponse.RelatedWiki(
                        String.valueOf(related.id()),
                        related.title()
                ))
                .toList();
    }
```

> **순서가 바뀐다.** 앞 판본은 `wikiRefs` 의 저장 순서를 따랐다. 새 판본은 내 목록 순서 뒤에 역방향을 붙인다. `LinkedHashMap` 이 그 순서를 보존하고 `putIfAbsent` 가 중복을 막는다. 순서를 단정하는 기존 테스트가 있으면 그 기대값을 맞춘다.

필요한 import 를 추가한다 (이미 있으면 생략):

```java
import java.util.LinkedHashMap;
import java.util.Map;
```

- [ ] **Step 4: `WikiChatMessageService#relatedWikis` 에 같은 변경을 한다**

`WikiChatMessageService.java:252` 의 `relatedWikis` 도 자기 목록만 읽는다. **Step 3 과 같은 모양으로 바꾼다** — 인자에 `long wikiId` 를 추가하고, 호출부(`:221`)를 `relatedWikis(wiki.scopeKey(), wiki.id(), wiki.wikiRefs())` 로 바꾼다. 본문은 Step 3 의 코드를 그대로 쓴다.

> **이 변경은 테스트를 새로 쓰지 않는다.** Step 3 과 코드가 동일하고, `WikiChatMessageServiceTest:147`·`WikiChatMessageControllerTest:118` 이 이미 `relatedWikis` 를 단정해 회귀를 잡는다. 두 서비스의 조립이 갈라지는 것이 걱정되면 Step 3 의 테스트를 채팅 경로로 한 번 더 쓰되, **Step 3 과 이 단계의 본문이 글자까지 같은지 먼저 확인한다** — 다르면 그것이 결함이다.

> 두 곳에 같은 코드가 생긴다. `WikiQueryService:418` 에 **이미 그 중복을 지적하는 `TODO(팀 협업)` 주석이 있다** (「Wiki 상세 조립 로직은 WikiChatMessageService.toDetail 과 사실상 동일하다. 공용 컴포넌트로 추출할지 팀과 협의한다」). **이 계획에서 추출하지 않는다** — 팀 협의 항목이고, 여기서 리팩터링하면 이 MR 의 목적이 흐려진다. 대신 양쪽 주석에 「무방향 판정이 두 곳에 있다 — 위 TODO 의 추출 대상」을 한 줄 남긴다.

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test --tests '*WikiReadQueryServiceTest*' --tests '*WikiChatMessageServiceTest*'
```

Expected: 전부 PASS. `WikiChatMessageServiceTest:147` 이 `relatedWikis` 를 단정하므로 여기서 깨지면 기대값을 확인한다.

- [ ] **Step 6: 백엔드 전체 테스트로 회귀를 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

Expected: Task 0 기준선 대비 새 실패 없음. `WikiChatMessageControllerTest:118`·`DocumentUploadControllerTest:151`·`DocumentManagementServiceTest:127` 이 `relatedWikis` 를 단정하므로 특히 확인한다.

- [ ] **Step 7: 커밋**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git add backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiQueryService.java \
        backend/src/main/java/com/ajt/backend/domain/wiki/service/WikiChatMessageService.java \
        backend/src/test/java/com/ajt/backend/domain/wiki/service/WikiReadQueryServiceTest.java
git commit -m "$(cat <<'EOF'
fix(wiki): 관련 위키에 나를 가리키는 Wiki 도 함께 보여준다

관계가 무방향이므로(FR-WIKI-010) 내 목록만 읽으면 반쪽만 보인다. 보정 전에도 화면이
맞아야 하고, 이후 어떤 경로로 반쪽이 생겨도 화면은 옳아야 한다.

InternalWikiQueryService#relations 가 이미 같은 방식으로 역방향을 계산한다 — 그쪽이
온전한 관계를 보고 있어서 데이터가 반쪽인 것이 오래 드러나지 않았다.
EOF
)"
```

---

## Task 5: 실기동 확인과 MR

**Files:** 없음 (확인·문서)

**Interfaces:**
- Consumes: Task 1~4 의 결과
- Produces: MR

- [ ] **Step 1: 담당 범위 밖 변경이 섞이지 않았는지 확인한다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git status
git diff --stat origin/develop
```

Expected: `backend/src/**` 만 바뀌었다. `ai/`·`frontend/`·`docs/` 변경이 섞여 있으면 이 브랜치에서 빼낸다.

- [ ] **Step 2: develop 최신을 반영한다**

컨벤션이 rebase 를 쓰지 않는다.

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106
git fetch origin
git merge origin/develop
```

- [ ] **Step 3: 전체 테스트를 다시 돌린다**

```bash
cd /Users/wolyong/workspace/AJT/S15P11B106/backend
sh gradlew test
```

Expected: Task 0 기준선 대비 새 실패 없음.

- [ ] **Step 4: 실기동으로 확인한다 — 사용자 확인 후에만**

Spring + MySQL 을 띄우고 다음을 본다. `experiments/backend_sim.py` 를 쓰지 않는다.

1. 기동 로그에 보정 결과가 찍힌다 — `한쪽에만 저장돼 있던 Wiki 관계 N건을 대칭으로 채웠습니다` 또는 `보정 대상 없음`
2. MySQL 에서 대칭성을 직접 확인한다:

```sql
SELECT a.wiki_id AS src, b.wiki_id AS dst
FROM wiki a
JOIN wiki b ON b.scope_key = a.scope_key
WHERE JSON_CONTAINS(a.wiki_refs, CAST(b.wiki_id AS JSON))
  AND NOT JSON_CONTAINS(b.wiki_refs, CAST(a.wiki_id AS JSON));
```

Expected: **0행.** 한 방향만 있는 관계가 없다.

3. 화면에서 QA 때 반쪽이던 위키의 상세를 열어 관련 위키가 양쪽에 뜨는지 본다

**MySQL 데이터는 테스트 후에도 초기화하지 않는다.**

- [ ] **Step 5: MR 을 올린다 — 사용자 지시가 있을 때만**

승인 없이 push·MR 하지 않는다. 제목:

```
fix(wiki): Wiki 간 관계를 무방향으로 저장·조회한다 [S15P11B106-<티켓번호>]
```

본문에 담을 것:
- 증상(QA 확인): A 상세에는 B 가 뜨는데 B 상세는 비어 있다. 서로 링크한 경우만 정상처럼 보여 간헐적으로 나타났다
- 원인: `applyRelationChanges` 가 source 에만 썼다. `addWikiRef` 호출처가 저장소 전체에 그 한 줄뿐이었다
- 근거: FR-WIKI-010, DR-003. **관련 티켓 S15P11B106-83** 의 완료 조건이 충족되지 않았던 것
- 세 겹으로 고쳤다: 저장 / 기존 데이터 보정 / 읽기
- 테스트: 무방향 회귀 테스트를 새로 만들었다 (이전에는 0건)
- **협의 항목**: `WikiQueryService`·`WikiChatMessageService` 에 같은 조립 코드가 생겼다. `WikiQueryService:418` 의 기존 `TODO(팀 협업)` 추출 대상에 포함된다 — 이 MR 에서는 추출하지 않았다
- 함께 볼 티켓: **S15P11B106-256** 이 같은 `applyRelationChanges` 를 건드린다

---

## 이 계획에서 하지 않는 것

- **AI 서버 변경.** 한 방향으로 보내는 것이 옳다
- **ERD 변경.** `wiki.wiki_refs` 를 그대로 쓴다
- **`WikiDetailAssembler` 추출.** 기존 `TODO(팀 협업)` 항목이다. 이 MR 의 목적을 흐린다
- **Wiki-원본문서 관계(`document_refs`/`document_wiki_refs`)의 무방향성.** 같은 DR-003 조항이 걸려 있으나 별건이다
- **설계의 ①(목차)·③(주소 통일).** 각각 별도 계획·별도 MR 이다
