package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementLocks;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.PaymentFacts;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.negotiation.AgreementPaymentRecorded;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RS-102 (H07 parte 2b): quem recebeu o dinheiro vem do evento ({@code PaymentConfirmed.receivedBy}), fonte única —
 * mesmo que a consulta ao pagamento dissesse o contrário. Evento no formato anterior ao campo (nulo) mantém a dedução
 * pela origem do pagamento (ADR-0004; decisão 1630).
 */
class ReceiptPostingReceivedByTest {

    private final ReceiptRepository receipts = mock(ReceiptRepository.class);
    private final SettlementSources lookups = mock(SettlementSources.class);
    private final JsonCodec json = mock(JsonCodec.class);
    private final ReceiptPostingService service = new ReceiptPostingService(receipts,
            mock(SettlementFactRepository.class), lookups, mock(SettlementLocks.class), mock(RatingService.class),
            mock(SettlementMetrics.class), json);

    @Test
    void eventSaysCreditorWhilePaymentFactsSayAgency() {
        assertThat(post(PaymentConfirmed.RECEIVED_BY_CREDITOR, "AGENCY_ACCOUNT")).isEqualTo(ReceivedBy.CREDITOR);
    }

    @Test
    void eventSaysAgencyWhilePaymentFactsSayCreditor() {
        assertThat(post(PaymentConfirmed.RECEIVED_BY_AGENCY, "CREDITOR_DIRECT")).isEqualTo(ReceivedBy.AGENCY);
    }

    @Test
    void eventWithoutReceivedByFallsBackToPaymentFactsCreditor() {
        assertThat(post(null, "CREDITOR_DIRECT")).isEqualTo(ReceivedBy.CREDITOR);
    }

    @Test
    void eventWithoutReceivedByFallsBackToPaymentFactsAgency() {
        assertThat(post(null, "AGENCY_ACCOUNT")).isEqualTo(ReceivedBy.AGENCY);
    }

    @Test
    void agreementEventWithoutReceivedByFallsBackToPaymentFacts() {
        AgreementPaymentRecorded old = new AgreementPaymentRecorded(Ids.newId(), Ids.newId(), Instant.now(), Ids.newId(),
                Ids.newId(), Ids.newId(), Ids.newId(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, "APPLIED", "ACTIVE");
        assertThat(old.receivedBy()).isNull();
        assertThat(old.receivedByInformed()).isFalse();
        assertThat(ReceiptPostingService.receivedBy(old.receivedByInformed(), old.receivedByCreditor(),
                Optional.of(facts("CREDITOR_REPORT")))).isEqualTo(ReceivedBy.CREDITOR);
        assertThat(ReceiptPostingService.receivedBy(false, false, Optional.empty())).isEqualTo(ReceivedBy.AGENCY);
        assertThat(ReceiptPostingService.receivedBy(true, false, Optional.of(facts("CREDITOR_DIRECT"))))
                .isEqualTo(ReceivedBy.AGENCY);
    }

    private static PaymentFacts facts(String origin) {
        return new PaymentFacts(Ids.newId(), "CONFIRMED", "EXTERNAL", "PIX", origin, Instant.now(), null);
    }

    private ReceivedBy post(String receivedBy, String factsOrigin) {
        UUID allocation = Ids.newId();
        UUID title = Ids.newId();
        when(json.toJson(any())).thenReturn("{}");
        when(receipts.findByAllocationIdAndKind(any(), any())).thenReturn(List.of());
        when(receipts.save(any(Receipt.class))).thenAnswer(i -> i.getArgument(0));
        when(lookups.paymentOfAllocation(allocation)).thenReturn(Optional.of(facts(factsOrigin)));
        PaymentConfirmed event = new PaymentConfirmed(Ids.newId(), Ids.newId(), Instant.now(), allocation, Ids.newId(),
                Ids.newId(), Ids.newId(), null, new BigDecimal("100.00"), Instant.now(), "EXTERNAL", "PIX",
                List.of(new PaymentConfirmed.Allocation(title, null, new BigDecimal("100.00"), "T-1", 10, null)),
                receivedBy);
        service.paymentConfirmed(event, lookups);
        ArgumentCaptor<Receipt> saved = ArgumentCaptor.forClass(Receipt.class);
        verify(receipts).save(saved.capture());
        return saved.getValue().getReceivedBy();
    }
}
