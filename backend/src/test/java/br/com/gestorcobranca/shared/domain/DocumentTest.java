package br.com.gestorcobranca.shared.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTest {

    @Test
    void parsesFormattedCpfAndCnpj() {
        Document cpf = Document.parse("529.982.247-25");
        assertThat(cpf.type()).isEqualTo(Document.DocumentType.CPF);
        assertThat(cpf.number()).isEqualTo("52998224725");

        Document cnpj = Document.parse("11.222.333/0001-81");
        assertThat(cnpj.type()).isEqualTo(Document.DocumentType.CNPJ);
        assertThat(cnpj.formatted()).isEqualTo("11.222.333/0001-81");
    }

    @Test
    void rejectsInvalidCheckDigitsAndRepeatedDigits() {
        assertThatThrownBy(() -> Document.parse("529.982.247-24")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Document.parse("111.111.111-11")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Document.parse("11.222.333/0001-82")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Document.parse("123")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void neverExposesFullNumberInToString() {
        Document cpf = Document.parse("52998224725");
        assertThat(cpf.toString()).doesNotContain("52998224725").contains("***.982.247-**");
    }
}
