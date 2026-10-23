package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.CampaignPurpose;
import br.com.gestorcobranca.campaign.domain.model.PauseReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static br.com.gestorcobranca.campaign.domain.CampaignFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

class PauseRulesTest {

    private static final CampaignPurpose COLLECTION = CampaignPurpose.EXTRAJUDICIAL_COLLECTION;

    @Test
    void caseSituationsThatSuspendTheCadence() {
        LocalDate due = LocalDate.of(2026, 9, 1);
        assertThat(PauseRules.blocking(snapshot("LEGAL", false, "1", due), COLLECTION)).contains(PauseReason.CASE_LEGAL);
        assertThat(PauseRules.blocking(snapshot("SUSPENDED", false, "1", due), COLLECTION))
                .contains(PauseReason.CASE_SUSPENDED);
        assertThat(PauseRules.blocking(snapshot("DISPUTED", false, "1", due), COLLECTION))
                .contains(PauseReason.DISPUTE_OPENED);
        assertThat(PauseRules.blocking(snapshot("AGREEMENT", true, "1", due), COLLECTION))
                .contains(PauseReason.AGREEMENT_FORMALIZED);
        assertThat(PauseRules.blocking(snapshot("OPEN", true, "1", due), COLLECTION)).isEmpty();
    }

    @Test
    void agreementFollowUpCampaignsKeepRunningDuringTheAgreement() {
        AudienceSnapshot agreement = snapshot("AGREEMENT", true, "1", LocalDate.of(2026, 9, 1));
        assertThat(PauseRules.blocking(agreement, CampaignPurpose.AGREEMENT_FOLLOW_UP)).isEmpty();
        assertThat(PauseRules.pausesOnPayment(CampaignPurpose.AGREEMENT_FOLLOW_UP)).isFalse();
    }

    @Test
    void resumeOnlyWhenTheCauseIsGoneAndNeverForManualOrPayment() {
        AudienceSnapshot open = snapshot("OPEN", true, "1", LocalDate.of(2026, 9, 1));
        assertThat(PauseRules.cleared(PauseReason.DISPUTE_OPENED, open, COLLECTION)).isTrue();
        assertThat(PauseRules.cleared(PauseReason.MANUAL, open, COLLECTION)).isFalse();
        assertThat(PauseRules.cleared(PauseReason.PAYMENT_CONFIRMED, open, COLLECTION)).isFalse();
        assertThat(PauseRules.cleared(PauseReason.CASE_LEGAL, snapshot("LEGAL", false, "1", null), COLLECTION)).isFalse();
    }
}
