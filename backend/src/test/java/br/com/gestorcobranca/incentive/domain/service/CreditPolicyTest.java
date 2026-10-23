package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.attribution.AttributionCredit;
import br.com.gestorcobranca.incentive.domain.model.attribution.AttributionParticipant;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.attribution.ParticipantActorType;
import br.com.gestorcobranca.incentive.domain.model.attribution.ParticipantRole;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.AttributionRules;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pesos de crédito por origem (§3.2), influência (portal/campanha) e renegociação (§3.4). */
class CreditPolicyTest {

    private static final UUID HUMAN = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ASSISTANT = UUID.randomUUID();
    private static final UUID TEAM = UUID.randomUUID();
    private static final AttributionRules RULES = StandardPlans.padraoMercado().attribution();

    private static List<AttributionParticipant> humanCloser() {
        return List.of(new AttributionParticipant(ParticipantActorType.ASSISTANT, ASSISTANT, ParticipantRole.PRE_NEGOTIATOR),
                new AttributionParticipant(ParticipantActorType.USER, HUMAN, ParticipantRole.CLOSER));
    }

    private static Optional<AttributionCredit> individual(List<AttributionCredit> credits) {
        return credits.stream().filter(c -> c.getBeneficiaryType() == BeneficiaryType.USER).findFirst();
    }

    @ParameterizedTest
    @CsvSource({"HUMAN,100", "HUMAN_COPILOT,100", "ASSISTANT_HANDOFF_NEGOTIATED,100", "ASSISTANT_HANDOFF_CONFIRMED,30"})
    void closerOriginsCreditTheHumanCloserWithTheOriginWeight(AgreementOrigin origin, String weight) {
        List<AttributionCredit> credits = CreditPolicy.resolve(origin, humanCloser(), TEAM, RULES, Optional.empty(),
                Optional.empty());
        AttributionCredit credit = individual(credits).orElseThrow();
        assertThat(credit.getBeneficiaryId()).isEqualTo(HUMAN);
        assertThat(credit.getRole()).isEqualTo(ParticipantRole.CLOSER);
        assertThat(credit.getWeight().value()).isEqualByComparingTo(weight);
        assertThat(credits).anyMatch(c -> c.getBeneficiaryType() == BeneficiaryType.TEAM && c.getBeneficiaryId().equals(TEAM));
    }

    @Test
    void autonomousAssistantAgreementOnlyFeedsTheTeamPool() {
        List<AttributionCredit> credits = CreditPolicy.resolve(AgreementOrigin.ASSISTANT_AUTONOMOUS,
                List.of(new AttributionParticipant(ParticipantActorType.ASSISTANT, ASSISTANT, ParticipantRole.CLOSER)),
                TEAM, RULES, Optional.empty(), Optional.empty());
        assertThat(credits).singleElement().satisfies(c -> {
            assertThat(c.getBeneficiaryType()).isEqualTo(BeneficiaryType.TEAM);
            assertThat(c.effectiveFraction()).isEqualByComparingTo("1");
        });
    }

    @Test
    void portalCreditsTheInfluencerFoundInTheWindowOverTheOneInformedByTheEvent() {
        List<AttributionParticipant> participants = List.of(
                new AttributionParticipant(ParticipantActorType.DEBTOR, UUID.randomUUID(), ParticipantRole.CLOSER),
                new AttributionParticipant(ParticipantActorType.USER, OTHER, ParticipantRole.INFLUENCER));
        AttributionCredit fromWindow = individual(CreditPolicy.resolve(AgreementOrigin.PORTAL_SELF_SERVICE, participants,
                null, RULES, Optional.of(HUMAN), Optional.empty())).orElseThrow();
        assertThat(fromWindow.getBeneficiaryId()).isEqualTo(HUMAN);
        assertThat(fromWindow.getWeight().value()).isEqualByComparingTo("50");
        assertThat(fromWindow.getReason()).isEqualTo(CreditPolicy.INFLUENCE_WINDOW);

        AttributionCredit fromEvent = individual(CreditPolicy.resolve(AgreementOrigin.CAMPAIGN, participants, null, RULES,
                Optional.empty(), Optional.empty())).orElseThrow();
        assertThat(fromEvent.getBeneficiaryId()).isEqualTo(OTHER);
        assertThat(fromEvent.getReason()).isEqualTo(CreditPolicy.INFLUENCER_FROM_EVENT);

        assertThat(CreditPolicy.resolve(AgreementOrigin.PORTAL_SELF_SERVICE, participants.subList(0, 1), null, RULES,
                Optional.empty(), Optional.empty())).isEmpty();
    }

    @Test
    void ownRenegotiationAppliesTheReducedWeightOnTopOfTheOriginWeight() {
        AttributionCredit own = individual(CreditPolicy.resolve(AgreementOrigin.ASSISTANT_HANDOFF_CONFIRMED, humanCloser(),
                null, RULES, Optional.empty(), Optional.of(HUMAN))).orElseThrow();
        // 30% × 50% = 15%
        assertThat(own.effectiveFraction()).isEqualByComparingTo("0.15");
        assertThat(own.getReason()).endsWith("OWN_RENEGOTIATION");
        AttributionCredit other = individual(CreditPolicy.resolve(AgreementOrigin.HUMAN, humanCloser(), null, RULES,
                Optional.empty(), Optional.of(OTHER))).orElseThrow();
        assertThat(other.effectiveFraction()).isEqualByComparingTo("1");
    }

    @ParameterizedTest
    @CsvSource({
            // novo, substituído, pago no substituído, incremental
            "8000.00,10000.00,3000.00,1000.00",
            "6000.00,10000.00,3000.00,0.00",
            "12000.00,10000.00,0.00,2000.00",
            "5000.00,10000.00,10000.00,5000.00"})
    void incrementalAmountOfARenegotiation(String amount, String replaced, String paid, String expected) {
        assertThat(CreditPolicy.incrementalAmount(Money.of(amount), Money.of(replaced), Money.of(paid)))
                .isEqualTo(Money.of(expected));
    }

    @Test
    void incrementalIsTheWholeAmountWhenThereIsNoReplacedAgreement() {
        assertThat(CreditPolicy.incrementalAmount(Money.of("700.00"), null, null)).isEqualTo(Money.of("700.00"));
    }
}
