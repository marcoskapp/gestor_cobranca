package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Confirmação de identidade para o atendimento automatizado sem revelar o documento. */
class DebtorIdentityMatchTest {

    private static final LocalDate BIRTH = LocalDate.of(1985, 3, 15);

    private static Debtor debtor(String document, LocalDate birth) {
        return new Debtor(Document.parse(document), "hash", "Maria Devedora", birth, null);
    }

    @Test
    void matchesPrefixAndBirthDate() {
        Debtor d = debtor("529.982.247-25", BIRTH);
        assertThat(d.matchesIdentity("529", BIRTH)).isTrue();
        assertThat(d.matchesIdentity("529982", BIRTH)).isTrue();
        assertThat(d.matchesIdentity("528", BIRTH)).isFalse();
        assertThat(d.matchesIdentity("529", BIRTH.plusDays(1))).isFalse();
        assertThat(d.matchesIdentity("52", BIRTH)).isFalse();
        assertThat(d.matchesIdentity("52998224725", BIRTH)).isFalse();
    }

    @Test
    void neverMatchesWithoutDataOrAfterAnonymization() {
        assertThat(debtor("529.982.247-25", null).matchesIdentity("529", BIRTH)).isFalse();
        assertThat(debtor("11.222.333/0001-81", null).matchesIdentity("112", BIRTH)).isFalse();
        Debtor d = debtor("529.982.247-25", BIRTH);
        d.anonymize(Instant.now());
        assertThat(d.matchesIdentity("529", BIRTH)).isFalse();
    }
}
