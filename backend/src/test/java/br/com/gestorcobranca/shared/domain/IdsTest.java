package br.com.gestorcobranca.shared.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void generatesVersion7IdsInStrictOrder() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).allSatisfy(id -> {
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        });
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i).compareTo(ids.get(i - 1)))
                    .as("ids devem ser crescentes na mesma instância").isPositive();
        }
    }
}
