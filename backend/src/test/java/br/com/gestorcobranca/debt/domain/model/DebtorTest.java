package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebtorTest {

    private static final Document CPF = Document.parse("529.982.247-25");
    private static final Document CNPJ = Document.parse("11.222.333/0001-81");

    @Test
    void personTypeDerivesFromDocument() {
        assertThat(new Debtor(CPF, "h", "Maria", null, null).getPersonType()).isEqualTo(PersonType.INDIVIDUAL);
        assertThat(new Debtor(CNPJ, "h", "Empresa", null, null).getPersonType()).isEqualTo(PersonType.COMPANY);
    }

    @Test
    void exposesOnlyMaskedDocument() {
        Debtor debtor = new Debtor(CPF, "h", "  Maria   da  Silva ", LocalDate.of(1980, 5, 1), null);
        assertThat(debtor.getName()).isEqualTo("Maria da Silva");
        assertThat(debtor.maskedDocument()).isEqualTo("***.982.247-**");
        assertThat(debtor.document()).contains(CPF);
    }

    @Test
    void importUpdateReportsChangesAndKeepsBirthDateWhenAbsent() {
        Debtor debtor = new Debtor(CPF, "h", "Maria", LocalDate.of(1980, 5, 1), "nota interna");
        assertThat(debtor.updateFromImport("Maria", null)).isFalse();
        assertThat(debtor.getBirthDate()).isEqualTo(LocalDate.of(1980, 5, 1));
        assertThat(debtor.updateFromImport("Maria Souza", null)).isTrue();
        assertThat(debtor.getNotes()).isEqualTo("nota interna");
    }

    @Test
    void anonymizationRemovesPersonalDataAndFreezesRecord() {
        Debtor debtor = new Debtor(CPF, "h", "Maria", LocalDate.of(1980, 5, 1), "nota");
        Instant now = Instant.parse("2026-10-22T12:00:00Z");
        debtor.anonymize(now);

        assertThat(debtor.getName()).isEqualTo(Debtor.ANONYMIZED_NAME);
        assertThat(debtor.document()).isEmpty();
        assertThat(debtor.maskedDocument()).isNull();
        assertThat(debtor.getDocumentHash()).isNull();
        assertThat(debtor.getBirthDate()).isNull();
        assertThat(debtor.getNotes()).isNull();
        assertThat(debtor.getStatus()).isEqualTo(DebtorStatus.ANONYMIZED);
        assertThat(debtor.getAnonymizedAt()).isEqualTo(now);

        assertThatThrownBy(() -> debtor.update("Outro", null, null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> debtor.anonymize(now)).isInstanceOf(ConflictException.class);
    }
}
