package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataProcessorTest {

    private static DataProcessor.Details details(String country, TransferSafeguard safeguard, LocalDate until,
                                                 boolean active) {
        return new DataProcessor.Details(" Mensageria Exemplo Ltda. ", ProcessorRole.SUB_OPERATOR, "Entrega de mensagens",
                "Nome, telefone e conteúdo das mensagens", country, safeguard, "DPA-2026-01", LocalDate.of(2026, 1, 1),
                until, active);
    }

    @Test
    void internationalTransferRequiresTheArticle33Safeguard() {
        assertThatThrownBy(() -> new DataProcessor("communication.whatsapp_cloud", details("us", null, null, true)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "data_processor.safeguard_required");

        DataProcessor abroad = new DataProcessor("communication.whatsapp_cloud",
                details("us", TransferSafeguard.STANDARD_CONTRACTUAL_CLAUSES, null, true));
        assertThat(abroad.getCountryCode()).isEqualTo("US");
        assertThat(abroad.isInternationalTransfer()).isTrue();
        assertThat(abroad.getName()).isEqualTo("Mensageria Exemplo Ltda.");

        // no Brasil a garantia não se aplica e é descartada
        DataProcessor local = new DataProcessor("telephony.tarifando",
                details("BR", TransferSafeguard.SPECIFIC_CONSENT, null, true));
        assertThat(local.getTransferSafeguard()).isNull();
        assertThat(local.isInternationalTransfer()).isFalse();
    }

    @Test
    void validatesKeyCountryAndPeriod() {
        assertThatThrownBy(() -> new DataProcessor("Twilio", details("BR", null, null, true)))
                .hasFieldOrPropertyWithValue("code", "data_processor.key_invalid");
        assertThatThrownBy(() -> new DataProcessor("telephony.twilio", details("Brasil", null, null, true)))
                .hasFieldOrPropertyWithValue("code", "data_processor.country_invalid");
        assertThatThrownBy(() -> new DataProcessor("telephony.twilio", details("BR", null, LocalDate.of(2025, 1, 1), true)))
                .hasFieldOrPropertyWithValue("code", "data_processor.period_invalid");
        assertThat(new DataProcessor("payment.pix_account:6f1c2b9e-1d1a-4c55-9c1e-2b7d1c0a9e11",
                details("BR", null, null, true)).getProviderKey()).startsWith("payment.pix_account:");
    }

    @Test
    void currentOnlyWhileActiveAndWithinTheContract() {
        DataProcessor p = new DataProcessor("telephony.twilio",
                details("US", TransferSafeguard.STANDARD_CONTRACTUAL_CLAUSES, LocalDate.of(2026, 12, 31), true));
        assertThat(p.isCurrentOn(LocalDate.of(2026, 11, 11))).isTrue();
        assertThat(p.isCurrentOn(LocalDate.of(2027, 1, 1))).isFalse();
        p.update(details("US", TransferSafeguard.STANDARD_CONTRACTUAL_CLAUSES, null, false));
        assertThat(p.isCurrentOn(LocalDate.of(2026, 11, 11))).isFalse();
    }
}
