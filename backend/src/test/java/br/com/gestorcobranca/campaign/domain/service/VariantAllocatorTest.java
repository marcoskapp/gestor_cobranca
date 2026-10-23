package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.model.ExperimentVariant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Atribuição aleatória estável por devedor, com grupo de controle e pesos. */
class VariantAllocatorTest {

    private final List<ExperimentVariant> variants = List.of(new ExperimentVariant("A", "Atual", 1, null),
            new ExperimentVariant("B", "Novo texto", 3, null));

    @Test
    void sameDebtorAlwaysFallsInTheSameArm() {
        UUID debtor = UUID.randomUUID();
        VariantAllocator.Arm first = VariantAllocator.allocate("sal", debtor, BigDecimal.TEN, variants);
        for (int i = 0; i < 20; i++) {
            assertThat(VariantAllocator.allocate("sal", debtor, BigDecimal.TEN, variants)).isEqualTo(first);
        }
        assertThat(first.bucket()).isBetween(0, VariantAllocator.BUCKETS - 1);
    }

    @Test
    void distributionFollowsControlShareAndWeights() {
        Map<String, Integer> counts = new HashMap<>();
        int n = 40_000;
        for (int i = 0; i < n; i++) {
            counts.merge(VariantAllocator.allocate("experimento-1", UUID.randomUUID(), new BigDecimal("20"), variants)
                    .code(), 1, Integer::sum);
        }
        assertThat(counts.get(ExperimentVariant.CONTROL) / (double) n).isCloseTo(0.20, within(0.015));
        assertThat(counts.get("A") / (double) n).isCloseTo(0.20, within(0.015));
        assertThat(counts.get("B") / (double) n).isCloseTo(0.60, within(0.015));
    }

    @Test
    void saltSeparatesExperimentsAndZeroControlHasNoHoldout() {
        int different = 0;
        for (int i = 0; i < 200; i++) {
            UUID debtor = UUID.randomUUID();
            if (VariantAllocator.bucket("x", debtor) != VariantAllocator.bucket("y", debtor)) {
                different++;
            }
            assertThat(VariantAllocator.allocate("x", debtor, BigDecimal.ZERO, variants).control()).isFalse();
        }
        assertThat(different).isGreaterThan(190);
    }
}
