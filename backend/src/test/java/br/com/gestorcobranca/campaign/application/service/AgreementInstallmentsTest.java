package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.service.AgreementInstallments.Lookup;
import br.com.gestorcobranca.campaign.domain.model.SkipReason;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.shared.paging.PageResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Escolha da próxima parcela em aberto do acordo em vigor do caso. */
class AgreementInstallmentsTest {

    private static final UUID CASE = UUID.randomUUID();
    private static final UUID DEBTOR = UUID.randomUUID();
    private static final UUID CREDITOR = UUID.randomUUID();
    private static final UUID PORTFOLIO = UUID.randomUUID();

    private final AgreementUseCases agreements = mock(AgreementUseCases.class);
    private final AgreementInstallments installments = new AgreementInstallments(agreements);

    private static InstallmentView installment(int number, String due, String amount, String paid, String status) {
        return new InstallmentView(UUID.randomUUID(), number, LocalDate.parse(due), new BigDecimal(amount),
                new BigDecimal(paid), status, null);
    }

    private static AgreementView agreement(String status, UUID caseId, UUID portfolioId, List<InstallmentView> parcels) {
        return new AgreementView(UUID.randomUUID(), status, null, null, CREDITOR, DEBTOR, portfolioId, caseId, null, 1, 0,
                null, "PHONE", "HUMAN", List.of(), null, null, null, null, null, null, null, null, List.of(), parcels,
                null, null, null, null, null, null, null, 0, null, null, false, 0);
    }

    private void given(AgreementView... views) {
        when(agreements.search(any(), any())).thenReturn(new PageResponse<>(List.of(views), 0, 200, views.length, 1));
    }

    private Lookup lookup() {
        return installments.next(CASE, DEBTOR, CREDITOR, PORTFOLIO);
    }

    @Test
    void nextOpenInstallmentOfTheActiveAgreementWithTheRemainingAmount() {
        given(agreement("IN_PAYMENT", CASE, PORTFOLIO, List.of(
                installment(1, "2026-10-05", "100.00", "100.00", "PAID"),
                installment(3, "2026-12-05", "100.00", "0.00", "OPEN"),
                installment(2, "2026-11-05", "100.00", "30.00", "PARTIALLY_PAID"))));
        Lookup found = lookup();
        assertThat(found.available()).isTrue();
        assertThat(found.installment().number()).isEqualTo(2);
        assertThat(found.installment().dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(found.installment().amount()).isEqualByComparingTo("70.00");
        assertThat(installments.nextDueDate(CASE, DEBTOR, CREDITOR, PORTFOLIO)).contains(LocalDate.of(2026, 11, 5));
    }

    @Test
    void overdueInstallmentIsStillTheNextOpenOne() {
        given(agreement("IN_PAYMENT", CASE, PORTFOLIO, List.of(
                installment(1, "2026-09-05", "100.00", "0.00", "OVERDUE"),
                installment(2, "2026-10-05", "100.00", "0.00", "OPEN"))));
        assertThat(lookup().installment().number()).isEqualTo(1);
    }

    @Test
    void agreementsNotInForceOrOfAnotherCaseAreIgnored() {
        given(agreement("BROKEN", CASE, PORTFOLIO, List.of(installment(1, "2026-11-05", "100.00", "0.00", "OPEN"))),
                agreement("FORMALIZED", UUID.randomUUID(), PORTFOLIO,
                        List.of(installment(1, "2026-11-05", "100.00", "0.00", "OPEN"))),
                agreement("FORMALIZED", null, UUID.randomUUID(),
                        List.of(installment(1, "2026-11-05", "100.00", "0.00", "OPEN"))));
        assertThat(lookup()).isEqualTo(Lookup.unavailable(SkipReason.NO_ACTIVE_AGREEMENT));
        assertThat(installments.nextDueDate(CASE, DEBTOR, CREDITOR, PORTFOLIO)).isEmpty();
    }

    @Test
    void agreementWithoutCaseMatchesByPortfolio() {
        given(agreement("FORMALIZED", null, PORTFOLIO, List.of(installment(1, "2026-11-05", "80.00", "0.00", "OPEN"))));
        assertThat(lookup().installment().amount()).isEqualByComparingTo("80.00");
    }

    @Test
    void activeAgreementWithoutOpenInstallment() {
        given(agreement("IN_PAYMENT", CASE, PORTFOLIO, List.of(installment(1, "2026-10-05", "100.00", "100.00", "PAID"),
                installment(2, "2026-11-05", "100.00", "0.00", "CANCELLED"))));
        assertThat(lookup()).isEqualTo(Lookup.unavailable(SkipReason.NO_OPEN_INSTALLMENT));
    }
}
