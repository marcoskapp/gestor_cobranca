package br.com.gestorcobranca.shared.paging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NameSearchTest {

    record Item(UUID id, String name) {
    }

    private final Item joao = new Item(UUID.randomUUID(), "João Silva");
    private final Item ana = new Item(UUID.randomUUID(), "Ana Souza");
    private final Item bruno = new Item(UUID.randomUUID(), "Bruno Lima");

    private PageResponse<String> page(String query, Set<UUID> ids, PageQuery page, int max) {
        return NameSearch.page(List.of(joao, ana, bruno), Item::id, Item::name, query, ids, page, max, Item::name);
    }

    @Test
    void searchesByNameIgnoringCaseAndAccentsInNameOrder() {
        assertThat(page("JOAO", null, new PageQuery(0, 20), 50).content()).containsExactly("João Silva");
        assertThat(page(null, null, new PageQuery(0, 20), 50).content())
                .containsExactly("Ana Souza", "Bruno Lima", "João Silva");
        assertThat(page("  ", Set.of(), new PageQuery(0, 20), 50).totalElements()).isEqualTo(3);
    }

    @Test
    void resolvesIdsAndPagesWithinTheLimit() {
        assertThat(page(null, Set.of(bruno.id(), UUID.randomUUID()), new PageQuery(0, 20), 50).content())
                .containsExactly("Bruno Lima");
        PageResponse<String> second = page(null, null, new PageQuery(1, 200), 2);
        assertThat(second.size()).isEqualTo(2);
        assertThat(second.content()).containsExactly("João Silva");
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(page(null, null, new PageQuery(5, 2), 2).content()).isEmpty();
    }
}
