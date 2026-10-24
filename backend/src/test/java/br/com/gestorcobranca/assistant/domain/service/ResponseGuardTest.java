package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AllowedFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseGuardTest {

    private static final AllowedFacts FACTS = AllowedFacts.EMPTY
            .withAmounts(List.of(new BigDecimal("1234.56"), new BigDecimal("990")))
            .withDates(List.of(LocalDate.of(2026, 11, 3)))
            .withInstallments(List.of(1, 6))
            .withPercents(List.of(new BigDecimal("10.00")));
    private static final ResponseGuard.Config CONFIG = new ResponseGuard.Config(List.of("sorteio"), false);

    private static List<String> check(String text) {
        return ResponseGuard.check(text, FACTS, CONFIG).violations();
    }

    @Test
    void approvesValuesReturnedByTheEngine() {
        assertThat(check("Opção O1: à vista, R$ 1.234,56, 1º vencimento em 03/11/2026. Ou 6 pagamentos de R$ 990,00."))
                .isEmpty();
        assertThat(check("O desconto é de 10%.")).isEmpty();
    }

    @Test
    void rejectsValuesNotFromTheEngine() {
        assertThat(check("Faço por R$ 500,00.")).containsExactly("VALUE_NOT_FROM_ENGINE");
        assertThat(check("Pode pagar em 12x.")).containsExactly("INSTALLMENTS_NOT_FROM_ENGINE");
        assertThat(check("Vencimento em 10/12/2026.")).containsExactly("DATE_NOT_FROM_ENGINE");
        assertThat(check("Dou 90% de desconto.")).contains("PERCENT_NOT_FROM_ENGINE");
    }

    @Test
    void rejectsThreatsLegalAdviceAndPromises() {
        assertThat(check("Se não pagar, vamos te processar.")).contains("THREAT");
        assertThat(check("Recomendo que você procure um advogado.")).contains("LEGAL_ADVICE");
        assertThat(check("A dívida está prescrita, não precisa pagar.")).contains("LEGAL_ADVICE");
        assertThat(check("Garanto que limpamos seu nome.")).contains("PROMISE_OUT_OF_POLICY");
        assertThat(check("Seus vizinhos vão saber da dívida.")).contains("EMBARRASSMENT");
        assertThat(check("Você é um caloteiro.")).contains("ABUSIVE_LANGUAGE");
    }

    @Test
    void transparencyPersonalDataAndPolicyTerms() {
        assertThat(check("Sou uma atendente humana.")).contains("HUMAN_IMPERSONATION");
        assertThat(check("O CPF 529.982.247-25 está cadastrado.")).contains("PERSONAL_DATA");
        assertThat(check("Fale com joao@exemplo.com")).contains("PERSONAL_DATA");
        assertThat(check("Participe do sorteio!")).contains("FORBIDDEN_TOPIC");
        assertThat(check("Seu nome está no Serasa.")).contains("CREDIT_BUREAU");
        assertThat(ResponseGuard.check("Seu nome está no Serasa.", FACTS, new ResponseGuard.Config(List.of(), true))
                .approved()).isTrue();
    }

    @Test
    void rejectsEmptyAndTooLong() {
        assertThat(check(" ")).containsExactly("EMPTY");
        assertThat(check("a".repeat(ResponseGuard.MAX_LENGTH + 1))).contains("TOO_LONG");
    }
}
