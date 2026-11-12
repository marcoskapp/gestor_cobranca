package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementSummary.StatusTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgreementSummaryTest {

    private static StatusTotals row(AgreementStatus status, long n, String total, String paid) {
        return new StatusTotals(status, n, new BigDecimal(total), new BigDecimal(paid));
    }

    @Test
    void countsByStatusAndSumsAllAgreementsIncludingReplacedOnes() {
        AgreementSummary s = AgreementSummary.of(List.of(
                row(AgreementStatus.FORMALIZED, 1, "100.00", "0"),
                row(AgreementStatus.IN_PAYMENT, 2, "300.00", "120.50"),
                row(AgreementStatus.SETTLED, 3, "900.00", "900.00"),
                row(AgreementStatus.BROKEN, 1, "200.00", "40.00"),
                row(AgreementStatus.RENEGOTIATED, 1, "250.00", "50.00"),
                row(AgreementStatus.CANCELLED, 1, "80.00", "0")));

        assertThat(s.total()).isEqualTo(9);
        assertThat(s.active()).isEqualTo(3);
        assertThat(s.settled()).isEqualTo(3);
        assertThat(s.broken()).isEqualTo(1);
        assertThat(s.renegotiated()).isEqualTo(1);
        assertThat(s.cancelled()).isEqualTo(1);
        assertThat(s.totalAmount()).isEqualByComparingTo("1830.00");
        assertThat(s.paidAmount()).isEqualByComparingTo("1110.50");
    }

    @Test
    void fulfillmentIsSettledOverSettledPlusBrokenIgnoringRenegotiatedAndCancelled() {
        AgreementSummary s = AgreementSummary.of(List.of(
                row(AgreementStatus.SETTLED, 2, "1", "1"),
                row(AgreementStatus.BROKEN, 1, "1", "0"),
                row(AgreementStatus.RENEGOTIATED, 5, "1", "0"),
                row(AgreementStatus.CANCELLED, 4, "1", "0")));

        assertThat(s.fulfillmentRate()).isEqualByComparingTo("0.6667");
    }

    @Test
    void withoutSettledOrBrokenTheFulfillmentIsUnknown() {
        assertThat(AgreementSummary.of(List.of(row(AgreementStatus.IN_PAYMENT, 1, "10", "5"))).fulfillmentRate()).isNull();
        AgreementSummary empty = AgreementSummary.of(List.of());
        assertThat(empty.total()).isZero();
        assertThat(empty.fulfillmentRate()).isNull();
        assertThat(empty.totalAmount()).isEqualByComparingTo("0");
    }
}
