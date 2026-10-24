package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.CompositionView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.OfferView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PaymentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationResult;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases.OfferSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PortalRulesTest {

    static final LocalDate D = LocalDate.of(2026, 10, 23);

    static OfferView offer(int level, int n, String total, boolean valid, boolean within) {
        List<PaymentView> schedule = new java.util.ArrayList<>();
        BigDecimal each = new BigDecimal(total).divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.DOWN);
        for (int i = 1; i <= n; i++) {
            schedule.add(new PaymentView(i, D.plusMonths(i - 1), each, BigDecimal.ZERO, each, BigDecimal.ZERO));
        }
        BigDecimal discount = new BigDecimal("2000.00").subtract(new BigDecimal(total));
        return new OfferView(level, "n" + level, n, valid, within, null, null, discount, null, new BigDecimal(total),
                schedule.getFirst().amount(), new BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO, schedule, List.of(),
                List.of());
    }

    @Test
    void onlyValidOffersWithinTheChannelAuthorityTheCheapestPerPlan() {
        CompositionView composition = new CompositionView(D, List.of(), null, new BigDecimal("2000.00"), BigDecimal.ZERO, 60);
        SimulationResult r = new SimulationResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, D, null,
                null, 1, 1, false, false, null, "PORTAL", composition, null, List.of(
                offer(0, 1, "2000.00", true, true), offer(1, 1, "1600.00", true, true),
                offer(0, 3, "2000.00", true, true), offer(1, 3, "1800.00", true, false),
                offer(1, 6, "1900.00", false, true)), List.of(), List.of(), 1, null, 5, 10);
        List<OfferSummary> best = PortalSelfServiceService.bestOffers(r);
        assertThat(best).extracting(OfferSummary::installments).containsExactly(1, 3);
        assertThat(best.getFirst().level()).isEqualTo(1);
        assertThat(best.getFirst().discountPercent()).isEqualByComparingTo("20.0");
        // acima da alçada do canal (withinAuthority = false) nunca é oferecido
        assertThat(best.get(1).total()).isEqualByComparingTo("2000.00");
        assertThat(best.get(1).installmentAmount()).isEqualByComparingTo("666.66");
    }

    @Test
    void knowledgeNormalization() {
        assertThat(KnowledgeVerifier.suffix("CT-0001/98-76")).isEqualTo("9876");
        assertThat(KnowledgeVerifier.suffix("ab")).isEqualTo("AB");
        assertThat(KnowledgeVerifier.parseDate("15/03/1985")).hasValue(LocalDate.of(1985, 3, 15));
        assertThat(KnowledgeVerifier.parseDate("1985-03-15")).hasValue(LocalDate.of(1985, 3, 15));
        assertThat(KnowledgeVerifier.parseDate("31/02/1985")).isEmpty();
        assertThat(KnowledgeVerifier.parseDate("ontem")).isEmpty();
    }

    @Test
    void firstNameOnly() {
        assertThat(PortalSessionService.firstName("MARIA DA SILVA")).isEqualTo("Maria");
        assertThat(PortalSessionService.firstName("  joão  ")).isEqualTo("João");
        assertThat(PortalSessionService.firstName(null)).isNull();
    }

    @Test
    void handoffSummaryHasNoPersonalData() {
        var r = new br.com.gestorcobranca.portal.domain.model.ServiceRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, UUID.randomUUID(), "ABCDEFGHJK",
                br.com.gestorcobranca.portal.domain.model.ServiceRequestReason.TALK_TO_AGENT,
                br.com.gestorcobranca.portal.domain.model.PortalChannel.WHATSAPP, "meu CPF é 529.982.247-25");
        String summary = ServiceRequestDispatchJob.summary(r);
        assertThat(summary).contains("ABCDEFGHJK").doesNotContain("529").contains("mensagem do devedor");
    }
}
