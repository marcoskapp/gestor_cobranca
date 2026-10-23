package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.service.ConcurrencyPolicy.Enroll;
import br.com.gestorcobranca.campaign.domain.service.ConcurrencyPolicy.Preempt;
import br.com.gestorcobranca.campaign.domain.service.ConcurrencyPolicy.Running;
import br.com.gestorcobranca.campaign.domain.service.ConcurrencyPolicy.Suppress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Um devedor não recebe duas campanhas incompatíveis ao mesmo tempo. */
class ConcurrencyPolicyTest {

    private final UUID campaign = UUID.randomUUID();
    private final Running low = new Running(UUID.randomUUID(), UUID.randomUUID(), 10);
    private final Running high = new Running(UUID.randomUUID(), UUID.randomUUID(), 200);

    @Test
    void withoutGroupOrRunningEnrollmentTheDebtorIsEnrolled() {
        assertThat(ConcurrencyPolicy.decide(campaign, null, 50, List.of(high))).isInstanceOf(Enroll.class);
        assertThat(ConcurrencyPolicy.decide(campaign, "COBRANCA", 50, List.of())).isInstanceOf(Enroll.class);
    }

    @Test
    void higherPriorityPreemptsLowerOnes() {
        assertThat(ConcurrencyPolicy.decide(campaign, "COBRANCA", 50, List.of(low)))
                .isEqualTo(new Preempt(List.of(low)));
    }

    @Test
    void equalOrHigherPriorityInProgressSuppressesTheNewCampaign() {
        assertThat(ConcurrencyPolicy.decide(campaign, "COBRANCA", 200, List.of(high))).isEqualTo(new Suppress(high));
        assertThat(ConcurrencyPolicy.decide(campaign, "COBRANCA", 50, List.of(low, high))).isEqualTo(new Suppress(high));
    }

    @Test
    void theSameCampaignNeverRunsTwiceForTheDebtorInAGroup() {
        Running own = new Running(UUID.randomUUID(), campaign, 1);
        assertThat(ConcurrencyPolicy.decide(campaign, "COBRANCA", 999, List.of(own))).isEqualTo(new Suppress(own));
    }
}
