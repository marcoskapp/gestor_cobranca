package br.com.gestorcobranca.assistant.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalDataRedactorTest {

    @Test
    void removesDocumentsContactsAndLongNumbers() {
        String out = PersonalDataRedactor.redact(
                "meu cpf 529.982.247-25, email maria@x.com.br, fone (11) 98765-4321, conta 12345678", false);
        assertThat(out).doesNotContain("529").doesNotContain("maria@").doesNotContain("98765").doesNotContain("12345678")
                .contains("[documento omitido]", "[e-mail omitido]", "[telefone omitido]", "[número omitido]");
    }

    @Test
    void keepsMoneyAndInstallmentsAfterIdentity() {
        assertThat(PersonalDataRedactor.redact("posso pagar R$ 1.200,50 em 3x", false)).isEqualTo("posso pagar R$ 1.200,50 em 3x");
    }

    @Test
    void strictModeHidesIdentityAnswer() {
        String out = PersonalDataRedactor.redact("529 e nasci em 15/03/1985", true);
        assertThat(out).doesNotContain("529").doesNotContain("1985").contains("[dígitos omitidos]", "[data omitida]");
    }
}
