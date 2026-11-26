package br.com.gestorcobranca.collection.domain.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autoatendimento (portal): o aviso próprio do jurídico só quando a retenção jurídica é o único motivo; restrição de
 * contato não conta (decisão 1642, SEG-20261126-PA04).
 */
class EligibilityTest {

    private static Eligibility of(EligibilityReason... reasons) {
        return Eligibility.of(reasons.length == 0 ? EnumSet.noneOf(EligibilityReason.class) : EnumSet.copyOf(List.of(reasons)),
                List.of());
    }

    @Test
    void legalHoldAloneOrWithContactRestrictionIsTheOnlyBlocker() {
        assertThat(of(EligibilityReason.CASE_LEGAL).onlyLegalHold()).isTrue();
        assertThat(of(EligibilityReason.CASE_LEGAL, EligibilityReason.CONTACT_RESTRICTED).onlyLegalHold()).isTrue();
    }

    @Test
    void anyOtherReasonTogetherWithTheLegalHoldIsNotOnlyLegal() {
        for (EligibilityReason other : List.of(EligibilityReason.DISPUTE_OPEN, EligibilityReason.TITLES_DISPUTED,
                EligibilityReason.DEBTOR_INSOLVENCY, EligibilityReason.PORTFOLIO_NOT_OPERABLE,
                EligibilityReason.CASE_SUSPENDED, EligibilityReason.DEBTOR_ANONYMIZED)) {
            assertThat(of(EligibilityReason.CASE_LEGAL, other).onlyLegalHold()).as(other.name()).isFalse();
        }
    }

    @Test
    void contactRestrictionAloneDoesNotBlockSelfService() {
        assertThat(of(EligibilityReason.CONTACT_RESTRICTED).selfServiceBlockers()).isEmpty();
        assertThat(of().selfServiceBlockers()).isEmpty();
        assertThat(of(EligibilityReason.DISPUTE_OPEN).onlyLegalHold()).isFalse();
    }
}
