package br.com.gestorcobranca.shared.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void masksCpfCnpjEmailAndPhoneInFreeText() {
        String masked = SensitiveDataMasker.mask(
                "devedor 529.982.247-25 empresa 11.222.333/0001-81 email joao.silva@exemplo.com.br tel (47) 99876-5432");
        assertThat(masked)
                .doesNotContain("529.982.247-25", "11.222.333/0001-81", "joao.silva@", "99876-5432")
                .contains("***.982.247-**", "**.222.333/0001-**", "j***@exemplo.com.br", "(47) *****-5432");
    }

    @Test
    void masksUnformattedCpf() {
        assertThat(SensitiveDataMasker.mask("cpf=52998224725")).isEqualTo("cpf=***.982.247-**");
    }

    @Test
    void keepsUuidsAndAmountsIntact() {
        String text = "acordo 01a16584-0340-70ed-a872-83e42519c2e8 valor 1500.00";
        assertThat(SensitiveDataMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void masksIntegrationTokenSecretKeepingPrefix() {
        String token = "gck_abcdefghijkl." + "Q".repeat(43);
        assertThat(SensitiveDataMasker.mask("Authorization: Bearer " + token)).isEqualTo("Authorization: Bearer gck_abcdefghijkl.***");
    }
}
