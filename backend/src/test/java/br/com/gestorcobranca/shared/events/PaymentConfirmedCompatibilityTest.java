package br.com.gestorcobranca.shared.events;

import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato do {@link PaymentConfirmed} com {@code receivedBy} (H07, decisão 1521): ida e volta pelo registro de
 * publicação e leitura do formato gravado antes do campo (publicação pendente republicada), que vale assessoria.
 */
class PaymentConfirmedCompatibilityTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void roundTripKeepsWhoReceived() {
        PaymentConfirmed original = new PaymentConfirmed(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-11-25T12:00:00Z"), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                null, new BigDecimal("10.00"), Instant.parse("2026-11-25T11:00:00Z"), "EXTERNAL", "PIX",
                List.of(new PaymentConfirmed.Allocation(UUID.randomUUID(), null, new BigDecimal("10.00"))),
                PaymentConfirmed.RECEIVED_BY_CREDITOR);

        PaymentConfirmed read = mapper.readValue(mapper.writeValueAsString(original), PaymentConfirmed.class);
        assertThat(read).isEqualTo(original);
        assertThat(read.receivedByCreditor()).isTrue();
    }

    @Test
    void storedFormatWithoutReceivedByIsTheAgency() {
        String stored = """
                {"eventId":"8d6f1c1e-0000-4000-8000-000000000001","tenantId":"8d6f1c1e-0000-4000-8000-000000000002",
                 "occurredAt":"2026-11-25T12:00:00Z","paymentId":"8d6f1c1e-0000-4000-8000-000000000003",
                 "debtorId":"8d6f1c1e-0000-4000-8000-000000000004","creditorId":"8d6f1c1e-0000-4000-8000-000000000005",
                 "portfolioId":null,"agreementId":null,"amount":10.00,"paidAt":"2026-11-25T11:00:00Z",
                 "source":"EXTERNAL","method":"PIX","allocations":[]}""";

        PaymentConfirmed read = mapper.readValue(stored, PaymentConfirmed.class);
        assertThat(read.receivedBy()).isEqualTo(PaymentConfirmed.RECEIVED_BY_AGENCY);
        assertThat(read.receivedByCreditor()).isFalse();
    }
}
