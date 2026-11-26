package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Item;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Origin;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RemittanceCutTest {

    private static CreditorErasureNotice withTitles(int titles) {
        UUID portfolio = Ids.newId();
        return new CreditorErasureNotice(Ids.newId(), Ids.newId(), Origin.RETENTION, Ids.newId(), Instant.now(),
                IntStream.range(0, titles).mapToObj(i -> new Item(portfolio, "R-" + i, null)).toList());
    }

    @Test
    void cutsAtTheNoticeThatWouldPassTheTitleCap() {
        List<CreditorErasureNotice> ordered = List.of(withTitles(1000), withTitles(1000), withTitles(1));
        assertThat(RemittanceCut.take(ordered, 2000)).hasSize(2);
        assertThat(RemittanceCut.take(ordered, 1999)).hasSize(1);
    }

    @Test
    void alwaysTakesAtLeastOneNotice() {
        assertThat(RemittanceCut.take(List.of(withTitles(3000), withTitles(1)), 2000)).hasSize(1);
        assertThat(RemittanceCut.take(List.of(), 2000)).isEmpty();
    }
}
