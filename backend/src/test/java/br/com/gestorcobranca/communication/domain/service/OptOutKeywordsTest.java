package br.com.gestorcobranca.communication.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptOutKeywordsTest {

    @Test
    void recognizesExactKeywordsIgnoringCaseAccentsAndPunctuation() {
        assertThat(OptOutKeywords.match("SAIR")).contains("SAIR");
        assertThat(OptOutKeywords.match("  parar! ")).contains("PARAR");
        assertThat(OptOutKeywords.match("Stop")).contains("STOP");
        assertThat(OptOutKeywords.match("Não quero mais receber.")).contains("NAO QUERO MAIS RECEBER");
    }

    @Test
    void ignoresSentencesThatOnlyContainTheWord() {
        assertThat(OptOutKeywords.match("vou sair do trabalho e pago amanhã")).isEmpty();
        assertThat(OptOutKeywords.match("quero parar de pagar juros")).isEmpty();
        assertThat(OptOutKeywords.match(null)).isEmpty();
    }
}
