package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.CampaignFixtures;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static br.com.gestorcobranca.campaign.domain.CampaignFixtures.criteria;
import static br.com.gestorcobranca.campaign.domain.CampaignFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentMatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private static List<String> unmet(SegmentCriteria c, AudienceSnapshot s) {
        return SegmentMatcher.unmet(c, CampaignFixtures.CREDITOR, null, s, NOW, TimeConfig.BUSINESS_ZONE);
    }

    @Test
    void combinesOverdueBalanceAndStatusFilters() {
        SegmentCriteria c = criteria(30, 90, "500.00", Set.of("open", "IN_NEGOTIATION"));
        assertThat(unmet(c, snapshot("OPEN", true, "800.00", TODAY.minusDays(45)))).isEmpty();
        assertThat(unmet(c, snapshot("OPEN", true, "800.00", TODAY.minusDays(10)))).containsExactly("minDaysOverdue");
        assertThat(unmet(c, snapshot("OPEN", true, "800.00", TODAY.minusDays(91)))).containsExactly("maxDaysOverdue");
        assertThat(unmet(c, snapshot("PROMISE", true, "100.00", TODAY.minusDays(45))))
                .containsExactly("minBalance", "caseStatuses");
    }

    @Test
    void boundariesAreInclusiveAndFutureDueDateCountsAsZeroDays() {
        SegmentCriteria c = criteria(0, 30, "800.00", null);
        assertThat(unmet(c, snapshot("OPEN", true, "800.00", TODAY.minusDays(30)))).isEmpty();
        assertThat(unmet(c, snapshot("OPEN", true, "800.00", TODAY.plusDays(5)))).isEmpty();
        assertThat(unmet(criteria(1, null, null, null), snapshot("OPEN", true, "1", TODAY.plusDays(5))))
                .containsExactly("minDaysOverdue");
    }

    @Test
    void eligibilityIsRequiredByDefaultAndCanBeRelaxed() {
        AudienceSnapshot ineligible = snapshot("OPEN", false, "100.00", TODAY.minusDays(40));
        assertThat(unmet(SegmentCriteria.any(), ineligible)).containsExactly("eligibleOnly");
        SegmentCriteria relaxed = new SegmentCriteria(null, null, null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null);
        assertThat(unmet(relaxed, ineligible)).isEmpty();
    }

    @Test
    void closedCasesNeverMatchAndCreditorOrPortfolioMustMatch() {
        assertThat(unmet(SegmentCriteria.any(), snapshot("CLOSED", true, "100.00", TODAY))).containsExactly("caseActive");
        AudienceSnapshot s = snapshot("OPEN", true, "100.00", TODAY);
        assertThat(SegmentMatcher.unmet(SegmentCriteria.any(), UUID.randomUUID(), null, s, NOW, TimeConfig.BUSINESS_ZONE))
                .containsExactly("creditor");
        assertThat(SegmentMatcher.unmet(SegmentCriteria.any(), null, UUID.randomUUID(), s, NOW, TimeConfig.BUSINESS_ZONE))
                .containsExactly("portfolio");
    }

    @Test
    void contactHistoryPromiseAgreementScoreAndOwnerCriteria() {
        AudienceSnapshot worked = new AudienceSnapshot(UUID.randomUUID(), UUID.randomUUID(), CampaignFixtures.CREDITOR,
                CampaignFixtures.PORTFOLIO, "PROMISE", null, true, List.of(), List.of("EMAIL"), new BigDecimal("900.00"),
                new BigDecimal("900.00"), TODAY.minusDays(60), "USER", 4, 1, NOW.minusSeconds(3 * 86_400),
                NOW.minusSeconds(20 * 86_400), 640, 2, 3, NOW);
        SegmentCriteria matching = new SegmentCriteria(null, null, null, null, null, null, null, "EMAIL", 2, 10, true, 2,
                15, true, false, 1, 600, 700, Set.of("USER"));
        assertThat(unmet(matching, worked)).isEmpty();
        SegmentCriteria failing = new SegmentCriteria(null, null, null, null, null, null, null, "WHATSAPP", null, 3,
                false, 5, 30, false, true, 3, 700, null, Set.of("QUEUE"));
        assertThat(unmet(failing, worked)).containsExactly("contactableChannel", "maxInteractions",
                "everEffectiveContact", "noInteractionDays", "noEffectiveContactDays", "pendingPromise",
                "activeAgreement", "minBrokenAgreements", "minScore", "ownerTypes");
    }

    @Test
    void scoreCriterionExcludesCasesWithoutScore() {
        SegmentCriteria scored = new SegmentCriteria(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, 0, null, null);
        assertThat(unmet(scored, snapshot("OPEN", true, "10.00", TODAY))).containsExactly("minScore");
    }

    @Test
    void neverContactedMatchesNoInteractionWindow() {
        SegmentCriteria c = new SegmentCriteria(null, null, null, null, null, null, null, null, null, 0, false, 7, 7, null,
                null, null, null, null, null);
        assertThat(unmet(c, snapshot("OPEN", true, "10.00", TODAY))).isEmpty();
    }

    @Test
    void validationRejectsInconsistentRanges() {
        assertThatThrownBy(() -> criteria(90, 30, null, null).validate()).hasMessageContaining("atraso");
        assertThatThrownBy(() -> criteria(null, null, "-1", null).validate()).hasMessageContaining("negativo");
        assertThatThrownBy(() -> criteria(null, null, null, Set.of("CLOSED")).validate())
                .hasMessageContaining("Situações válidas");
    }
}
