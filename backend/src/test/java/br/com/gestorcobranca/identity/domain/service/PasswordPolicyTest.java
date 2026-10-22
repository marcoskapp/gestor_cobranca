package br.com.gestorcobranca.identity.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(12);

    @Test
    void acceptsLongDiversePassword() {
        assertThatCode(() -> policy.validate("Correto#Cavalo42", "ana@alfa.local", "Ana Souza")).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortWeakCommonAndPersonalPasswords() {
        assertThatThrownBy(() -> policy.validate("Curta#1", null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> policy.validate("somenteletrasminusculas", null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> policy.validate("Cobranca@123", null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> policy.validate("Aaaaaaaaaa1!", null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> policy.validate("Mariana#2026x", "mariana@alfa.local", null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> policy.validate("Gonçalves#2026", null, "Pedro Gonçalves")).isInstanceOf(BusinessRuleException.class);
    }
}
