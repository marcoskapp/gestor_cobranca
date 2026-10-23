package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentNormalizerTest {

    @Test
    void acceptsMaskedCpfAndCnpj() {
        assertThat(DocumentNormalizer.normalize("529.982.247-25", null))
                .isEqualTo(new DocumentNormalizer.Result(Document.parse("52998224725"), false));
        assertThat(DocumentNormalizer.normalize("11.222.333/0001-81", null).document().type())
                .isEqualTo(Document.DocumentType.CNPJ);
    }

    @Test
    void restoresLeadingZerosLostBySpreadsheets() {
        // CPF 012.345.678-90 gravado como número
        DocumentNormalizer.Result result = DocumentNormalizer.normalize("1234567890", null);
        assertThat(result.document().number()).isEqualTo("01234567890");
        assertThat(result.padded()).isTrue();
        // CNPJ 01.234.567/0001-95 lido como 12 dígitos
        assertThat(DocumentNormalizer.normalize("1234567000195", true).document().number()).isEqualTo("01234567000195");
    }

    @Test
    void rejectsInvalidCheckDigitsOrLength() {
        assertThatThrownBy(() -> DocumentNormalizer.normalize("529.982.247-24", null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> DocumentNormalizer.normalize("1234", null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> DocumentNormalizer.normalize("123456789012345", null))
                .isInstanceOf(BusinessRuleException.class);
    }
}
