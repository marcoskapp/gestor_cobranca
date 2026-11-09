package br.com.gestorcobranca.debt.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickSearchTermTest {

    @Test
    void completeCpfOrCnpjIsADocumentAndNeverATitleCode() {
        QuickSearchTerm cpf = QuickSearchTerm.of(" 529.982.247-25 ");
        assertThat(cpf.document()).hasValueSatisfying(d -> assertThat(d.number()).isEqualTo("52998224725"));
        assertThat(cpf.titleCode()).isEmpty();
        assertThat(cpf.name()).isEmpty();
        assertThat(cpf.isPersonalData()).isTrue();

        assertThat(QuickSearchTerm.of("11222333000181").document()).isPresent();
    }

    @Test
    void completePhoneIsAPhone() {
        QuickSearchTerm phone = QuickSearchTerm.of("(11) 98765-4321");
        assertThat(phone.phone()).hasValueSatisfying(p -> assertThat(p.value()).isEqualTo("+5511987654321"));
        assertThat(phone.document()).isEmpty();
        assertThat(phone.titleCode()).isEmpty();
        assertThat(phone.isPersonalData()).isTrue();
    }

    @Test
    void partialDocumentOrPhoneSearchesNothingPersonal() {
        QuickSearchTerm partial = QuickSearchTerm.of("071.340");
        assertThat(partial.document()).isEmpty();
        assertThat(partial.phone()).isEmpty();
        assertThat(partial.isPersonalData()).isFalse();
        // sem espaço: ainda pode ser o código de um título no credor (exato)
        assertThat(partial.titleCode()).contains("071.340");
        assertThat(partial.name()).isEmpty();

        assertThat(QuickSearchTerm.of("529 982").isEmpty()).as("trecho com espaço não é código nem nome").isTrue();
    }

    @Test
    void nameNeedsThreeLettersOrDigits() {
        assertThat(QuickSearchTerm.of("Ma").isEmpty()).isTrue();
        assertThat(QuickSearchTerm.of("Mat").name()).contains("Mat");
        assertThat(QuickSearchTerm.of("José da").name()).contains("José da");
        assertThat(QuickSearchTerm.of("José da").titleCode()).isEmpty();
        assertThat(QuickSearchTerm.of("  ").isEmpty()).isTrue();
        assertThat(QuickSearchTerm.of(null).isEmpty()).isTrue();
    }

    @Test
    void codeWithLettersIsBothNameAndTitleCode() {
        QuickSearchTerm code = QuickSearchTerm.of("T-1234");
        assertThat(code.titleCode()).contains("T-1234");
        assertThat(code.name()).contains("T-1234");
        assertThat(code.isPersonalData()).isFalse();
    }

    @Test
    void rejectsLongTermsAndNeverPrintsTheText() {
        assertThatThrownBy(() -> QuickSearchTerm.of("x".repeat(81)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("debtor.quick_search_too_long"));
        assertThat(QuickSearchTerm.of("529.982.247-25").toString()).doesNotContain("529").doesNotContain("247");
    }
}
