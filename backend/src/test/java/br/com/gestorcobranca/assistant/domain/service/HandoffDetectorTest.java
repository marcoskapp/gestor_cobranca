package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.ExtraTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffDetectorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Eu não reconheço essa dívida | DISPUTE",
            "nunca contratei nada com vocês | DISPUTE",
            "Já paguei isso mês passado, tenho o comprovante | PAYMENT_CLAIM",
            "isso é golpe, clonaram meu cartão | FRAUD_SUSPECTED",
            "meu pai faleceu em agosto | DECEASED",
            "estou desempregado desde março | VULNERABILITY",
            "estou internada no hospital | VULNERABILITY",
            "sou superendividado, não tenho como | VULNERABILITY",
            "vou falar com meu advogado | LEGAL_MENTION",
            "vou no PROCON amanhã | LEGAL_MENTION",
            "isso vai virar processo | LEGAL_MENTION",
            "vou fazer uma reclamação no Reclame Aqui | COMPLAINT",
            "seu idiota, parem de me encher | ABUSIVE",
            "quero falar com um atendente | HUMAN_REQUESTED",
            "posso falar com uma pessoa de verdade? | HUMAN_REQUESTED",
            "não quero mais receber essas mensagens | OPT_OUT",
            "SAIR | OPT_OUT"
    })
    void detectsMandatoryTriggers(String text, HandoffReason expected) {
        assertThat(HandoffDetector.detect(text, List.of())).extracting(HandoffDetector.Detection::reason).contains(expected);
    }

    @Test
    void ignoresOrdinaryNegotiation() {
        assertThat(HandoffDetector.detect("Quero pagar à vista, qual o valor?", List.of())).isEmpty();
        assertThat(HandoffDetector.detect("posso parcelar em 3x?", List.of())).isEmpty();
        assertThat(HandoffDetector.detect("vou sair do trabalho às 18h e te respondo", List.of())).isEmpty();
    }

    @Test
    void prioritizesLegalRiskOverHumanRequest() {
        assertThat(HandoffDetector.first("quero falar com um humano, meu advogado mandou", List.of()))
                .get().extracting(HandoffDetector.Detection::reason).isEqualTo(HandoffReason.LEGAL_MENTION);
    }

    @Test
    void policyTriggersAreAddedNotReplacing() {
        List<ExtraTrigger> extras = List.of(new ExtraTrigger("cartão de crédito", "POLICY_TRIGGER"));
        assertThat(HandoffDetector.detect("é do cartao de credito?", extras)).extracting(HandoffDetector.Detection::reason)
                .containsExactly(HandoffReason.POLICY_TRIGGER);
        assertThat(HandoffDetector.detect("não reconheço", extras)).extracting(HandoffDetector.Detection::reason)
                .contains(HandoffReason.DISPUTE);
    }

    @Test
    void explicitAcceptanceIgnoresNegation() {
        assertThat(HandoffDetector.explicitAcceptance("Aceito a O1")).isTrue();
        assertThat(HandoffDetector.explicitAcceptance("pode fechar")).isTrue();
        assertThat(HandoffDetector.explicitAcceptance("não aceito")).isFalse();
        assertThat(HandoffDetector.explicitAcceptance("quanto fica?")).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "pode mandar o pix | true",
            "manda o código do pix por favor | true",
            "quero pagar agora | true",
            "vou pagar hoje no pix | true",
            "aceito, pode gerar | true",
            "pode mandar | true",
            "e o pix? | false",
            "não quero pagar agora | false",
            "agora não, pago depois | false",
            "manda o pix mês que vem | false",
            "quanto é a parcela? | false"})
    void explicitPaymentRequestFollowsTheExplicitWordsRule(String text, boolean expected) {
        assertThat(HandoffDetector.explicitPaymentRequest(text)).isEqualTo(expected);
    }
}
