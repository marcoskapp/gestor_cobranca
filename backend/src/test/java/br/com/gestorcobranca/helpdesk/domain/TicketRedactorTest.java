package br.com.gestorcobranca.helpdesk.domain;

import br.com.gestorcobranca.helpdesk.domain.service.TicketRedactor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Máscara determinística do texto da issue (repositório público): nenhum dado de devedor sai, mesmo que a IA o
 * escreva; termos do produto continuam legíveis.
 */
class TicketRedactorTest {

    private final TicketRedactor redactor = new TicketRedactor(Set.of("estação", "de", "cobrança", "acordos",
            "pagamentos", "importação", "carteiras", "conferência"));

    @Test
    void masksDocumentsContactsAndPostalCodes() {
        String out = redactor.redact("CPF 529.982.247-25, CNPJ 11.222.333/0001-81, também 52998224725; "
                + "e-mail maria.souza@exemplo.com.br, fone (11) 98765-4321 ou +55 11 987654321, CEP 01310-100.", List.of());
        assertThat(out).doesNotContain("529").doesNotContain("11.222").doesNotContain("maria.souza")
                .doesNotContain("98765").doesNotContain("01310")
                .contains("[documento]").contains("[e-mail]").contains("[telefone]").contains("[cep]");
    }

    @Test
    void masksAmountsAndLongNumbers() {
        String out = redactor.redact("O acordo de R$ 1.234,56 (parcela de 250,00) do contrato 4500123 e título "
                + "TIT-88123/02 não baixou; total 12.500 também.", List.of());
        assertThat(out).doesNotContain("1.234,56").doesNotContain("250,00").doesNotContain("4500123")
                .doesNotContain("88123").doesNotContain("12.500").contains("[valor]").contains("[número]");
    }

    @Test
    void masksAddresses() {
        String out = redactor.redact("O endereço Rua das Flores, 123 está errado; também Av. Paulista 1000 apto 5.",
                List.of());
        assertThat(out).doesNotContain("Flores").doesNotContain("Paulista").contains("[endereço]");
    }

    @Test
    void masksKnownDebtorNamesEvenWhenPartial() {
        String out = redactor.redact("O devedor maria aparece duplicado; SOUZA também na outra carteira.",
                List.of("Maria Aparecida Souza"));
        assertThat(out).doesNotContainIgnoringCase("maria").doesNotContainIgnoringCase("souza")
                .doesNotContainIgnoringCase("aparecida").contains("[nome]");
    }

    @Test
    void masksUnknownProperNamesButKeepsProductVocabulary() {
        String out = redactor.redact("Na Estação de Cobrança o caso de João Pedro Albuquerque não abre. "
                + "Em Acordos e Pagamentos está certo.", List.of());
        assertThat(out).contains("Estação de Cobrança").contains("Acordos e Pagamentos")
                .doesNotContain("João").doesNotContain("Albuquerque").contains("[nome]");
    }

    @Test
    void keepsPlainProblemDescriptions() {
        String text = "Ao confirmar a conferência da importação aparece erro e nada acontece.";
        assertThat(redactor.redact(text, List.of())).isEqualTo(text);
    }
}
