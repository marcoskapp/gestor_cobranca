package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.TitleSituation;
import br.com.gestorcobranca.ingestion.domain.service.TitleSituationPolicy.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSituationPolicyTest {

    @Test
    void activeAndHoldingSituationsAlwaysWrite() {
        for (TitleSituation situation : new TitleSituation[]{null, TitleSituation.ACTIVE, TitleSituation.SUSPENDED,
                TitleSituation.DISPUTED}) {
            assertThat(TitleSituationPolicy.requiresLookup(situation)).isFalse();
            assertThat(TitleSituationPolicy.decide(situation, null)).isEqualTo(new Decision(true, false, null, null));
        }
    }

    @Test
    void closedAtCreditorNeverCreatesTitle() {
        Decision decision = TitleSituationPolicy.decide(TitleSituation.PAID, null);
        assertThat(decision.write()).isFalse();
        assertThat(decision.suspendExisting()).isFalse();
        assertThat(decision.warningCode()).isEqualTo("row.title_closed_not_found");
    }

    @ParameterizedTest
    @CsvSource({"PAID,OPEN,true,row.title_reported_paid", "CANCELLED,OPEN,true,row.title_reported_cancelled",
            "PAID,SUSPENDED,false,row.title_reported_paid", "PAID,SETTLED,false,", "CANCELLED,RETURNED,false,"})
    void closedAtCreditorSuspendsOpenTitleForReconciliation(TitleSituation situation, String status, boolean suspend,
                                                            String warning) {
        Decision decision = TitleSituationPolicy.decide(situation, status);
        assertThat(decision.write()).isFalse();
        assertThat(decision.suspendExisting()).isEqualTo(suspend);
        assertThat(decision.warningCode()).isEqualTo(warning);
    }

    @Test
    void suspendsAfterWriteOnlyWhenRequestedAndTitleIsOpen() {
        assertThat(TitleSituationPolicy.suspendAfterWrite(TitleSituation.DISPUTED, "OPEN")).isTrue();
        assertThat(TitleSituationPolicy.suspendAfterWrite(TitleSituation.SUSPENDED, "SUSPENDED")).isFalse();
        assertThat(TitleSituationPolicy.suspendAfterWrite(TitleSituation.SUSPENDED, "SETTLED")).isFalse();
        assertThat(TitleSituationPolicy.suspendAfterWrite(TitleSituation.ACTIVE, "OPEN")).isFalse();
        assertThat(TitleSituationPolicy.suspensionWarning(TitleSituation.DISPUTED)).isEqualTo("row.title_disputed_by_creditor");
        assertThat(TitleSituationPolicy.suspensionReason(TitleSituation.PAID, "abc")).contains("pagamento").contains("abc");
    }
}
