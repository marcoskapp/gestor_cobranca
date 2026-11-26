package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Item;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditorErasureNoticeTextTest {

    @Test
    void citesTitlesByPortfolioAndAsksForErasureAndCleanLoads() {
        UUID cards = Ids.newId();
        UUID loans = Ids.newId();
        String text = CreditorErasureNoticeText.compose("Banco A", Map.of(cards, "Cartões"), LocalDate.of(2026, 11, 20),
                List.of(new Item(cards, "R-7", 1), new Item(cards, "R-8", null), new Item(loans, "***.982.247-**", 2)));
        assertThat(text).contains("Prezados(as), Banco A", "Em 20/11/2026", "Carteira Cartões:\n- Título R-7, parcela 1\n"
                        + "- Título R-8\n", "Carteira não identificada:\n- Título ***.982.247-**, parcela 2",
                "art. 18, § 6º", "não incluam as linhas destes títulos", "não traz nome, documento nem contato");
    }

    @Test
    void remittanceListsEachSubjectWithoutIdentifyingThem() {
        UUID cards = Ids.newId();
        String text = CreditorErasureNoticeText.compose("Banco A", Map.of(cards, "Cartões"), List.of(
                new CreditorErasureNoticeText.Subject(LocalDate.of(2026, 11, 20), List.of(new Item(cards, "R-7", 1))),
                new CreditorErasureNoticeText.Subject(LocalDate.of(2026, 11, 21), List.of(new Item(cards, "R-9", null)))));
        assertThat(text).contains("titulares (LGPD", "dados pessoais de 2 titulares",
                "Titular 1 (dados eliminados em 20/11/2026):\nCarteira Cartões:\n- Título R-7, parcela 1",
                "Titular 2 (dados eliminados em 21/11/2026):\nCarteira Cartões:\n- Título R-9\n",
                "eliminem ou anonimizem estes titulares", "dos titulares.");
    }

    /** Decisão 1605: o credor do contrato em que o titular era coobrigado é avisado e pedido a não informá-lo mais. */
    @Test
    void coObligorContractIsListedAndAsksToStopInformingThePerson() {
        UUID cards = Ids.newId();
        String text = CreditorErasureNoticeText.compose("Banco A", Map.of(cards, "Cartões"), LocalDate.of(2026, 11, 20),
                List.of(new Item(cards, br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice
                        .coObligorReference("C-9"), null)));
        assertThat(text).contains("- Contrato C-9 (coobrigado)\n", "não informem mais esta pessoa como coobrigada.")
                .doesNotContain("Título Contrato");
        String withoutCoObligor = CreditorErasureNoticeText.compose("Banco A", Map.of(cards, "Cartões"),
                LocalDate.of(2026, 11, 20), List.of(new Item(cards, "R-7", 1)));
        assertThat(withoutCoObligor).contains("enviado de novo sem elas.\n\n").doesNotContain("coobrigad");
    }
}
