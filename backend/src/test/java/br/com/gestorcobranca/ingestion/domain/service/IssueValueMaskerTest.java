package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueValueMaskerTest {

    @Test
    void personalFieldsKeepOnlyTheLastTwoCharacters() {
        assertThat(IssueValueMasker.excerpt(CanonicalField.DEBTOR_DOCUMENT, "529.982.247-24")).isEqualTo("***.***.***-24");
        assertThat(IssueValueMasker.excerpt(CanonicalField.DEBTOR_NAME, "Maria Silva")).isEqualTo("***** ***va");
        assertThat(IssueValueMasker.excerpt(CanonicalField.EMAIL_1, "maria@x.com")).doesNotContain("maria");
    }

    @Test
    void otherFieldsMaskPersonalDataTypedByMistake() {
        assertThat(IssueValueMasker.excerpt(CanonicalField.PRODUCT, "Cartão 529.982.247-25"))
                .doesNotContain("529.982.247-25");
        assertThat(IssueValueMasker.excerpt(CanonicalField.DUE_DATE, "31/02/2026")).isEqualTo("31/02/2026");
    }

    @Test
    void truncatesAndIgnoresBlank() {
        assertThat(IssueValueMasker.excerpt(CanonicalField.PRODUCT, "x".repeat(100))).hasSize(IssueValueMasker.MAX_LENGTH);
        assertThat(IssueValueMasker.excerpt(CanonicalField.PRODUCT, "  ")).isNull();
    }
}
