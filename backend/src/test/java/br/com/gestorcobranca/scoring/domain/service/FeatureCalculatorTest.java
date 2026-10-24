package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.CaseFact;
import br.com.gestorcobranca.scoring.domain.model.CaseInputs;
import br.com.gestorcobranca.scoring.domain.model.FactKind;
import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureCalculatorTest {

    static final Instant AT = Instant.parse("2026-10-23T05:30:00Z");
    static final UUID CASE = UUID.randomUUID();
    static final UUID OTHER_CASE = UUID.randomUUID();
    static final UUID DEBTOR = UUID.randomUUID();
    static final UUID CREDITOR = UUID.randomUUID();
    static final UUID OTHER_CREDITOR = UUID.randomUUID();
    static final UUID PORTFOLIO = UUID.randomUUID();
    static final UUID OTHER_PORTFOLIO = UUID.randomUUID();

    static CaseInputs inputs(String status, String resume) {
        return new CaseInputs(CASE, DEBTOR, CREDITOR, PORTFOLIO, null, status, resume, 120, new BigDecimal("1500.00"), 2,
                AT.minus(Duration.ofDays(200)), AT.minus(Duration.ofDays(12)));
    }

    static CaseFact fact(FactKind kind, String status, UUID caseId, UUID creditor, UUID portfolio, UUID ref,
                         String channel, Duration ago) {
        Instant t = AT.minus(ago);
        return new CaseFact(kind, status, caseId, DEBTOR, creditor, portfolio, ref, channel, BigDecimal.TEN, t, t);
    }

    @Test
    void positionFeaturesComeFromTheCase() {
        FeatureVector v = FeatureCalculator.compute(inputs("PROMISE", null), List.of(), AT, 0.12);
        assertThat(v.get(Feature.DAYS_OVERDUE)).isEqualTo(120.0);
        assertThat(v.get(Feature.OPEN_BALANCE)).isEqualTo(1500.0);
        assertThat(v.get(Feature.OPEN_TITLES)).isEqualTo(2.0);
        assertThat(v.get(Feature.CASE_AGE_DAYS)).isEqualTo(200.0);
        assertThat(v.get(Feature.DAYS_SINCE_EFFECTIVE_CONTACT)).isEqualTo(12.0);
        assertThat(v.get(Feature.PENDING_PROMISE)).isEqualTo(1.0);
        assertThat(v.get(Feature.ACTIVE_AGREEMENT)).isEqualTo(0.0);
        assertThat(v.get(Feature.PORTFOLIO_RECOVERY_RATE)).isEqualTo(0.12);
        assertThat(v.get(Feature.DAYS_SINCE_LAST_PAYMENT)).isNull();
        // acordo guardado durante uma retenção conta como acordo em vigor
        assertThat(FeatureCalculator.compute(inputs("DISPUTED", "AGREEMENT"), List.of(), AT, null)
                .get(Feature.ACTIVE_AGREEMENT)).isEqualTo(1.0);
    }

    @Test
    void contactWindowsAndResponsiveChannels() {
        List<CaseFact> facts = new ArrayList<>();
        facts.add(fact(FactKind.CONTACT_ATTEMPT, null, CASE, CREDITOR, PORTFOLIO, null, "PHONE", Duration.ofDays(5)));
        facts.add(fact(FactKind.CONTACT_ATTEMPT, null, CASE, CREDITOR, PORTFOLIO, null, "PHONE", Duration.ofDays(100)));
        facts.add(fact(FactKind.CONTACT_ATTEMPT, null, OTHER_CASE, CREDITOR, OTHER_PORTFOLIO, null, "PHONE",
                Duration.ofDays(5)));
        facts.add(fact(FactKind.EFFECTIVE_CONTACT, null, CASE, CREDITOR, PORTFOLIO, null, "PHONE", Duration.ofDays(12)));
        facts.add(fact(FactKind.DEBTOR_RESPONSE, null, CASE, CREDITOR, PORTFOLIO, null, "WHATSAPP", Duration.ofDays(3)));
        facts.add(fact(FactKind.DEBTOR_RESPONSE, null, null, CREDITOR, null, null, "EMAIL", Duration.ofDays(20)));
        facts.add(fact(FactKind.DEBTOR_RESPONSE, null, null, OTHER_CREDITOR, null, null, "SMS", Duration.ofDays(20)));
        facts.add(fact(FactKind.DEBTOR_RESPONSE, null, CASE, CREDITOR, PORTFOLIO, null, "WHATSAPP", Duration.ofDays(1)));
        FeatureVector v = FeatureCalculator.compute(inputs("IN_NEGOTIATION", null), facts, AT, null);
        assertThat(v.get(Feature.CONTACT_ATTEMPTS_90D)).isEqualTo(1.0);
        assertThat(v.get(Feature.EFFECTIVE_CONTACTS_90D)).isEqualTo(1.0);
        // WhatsApp (caso) e e-mail (sem caso, mesmo credor); SMS é de outro credor
        assertThat(v.get(Feature.RESPONSIVE_CHANNELS_90D)).isEqualTo(2.0);
    }

    @Test
    void promisesUseTheLatestStatusOfEachPromise() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        List<CaseFact> facts = List.of(
                fact(FactKind.PROMISE, "PENDING", CASE, CREDITOR, PORTFOLIO, p1, null, Duration.ofDays(40)),
                fact(FactKind.PROMISE, "KEPT", CASE, CREDITOR, PORTFOLIO, p1, null, Duration.ofDays(35)),
                fact(FactKind.PROMISE, "PENDING", CASE, CREDITOR, PORTFOLIO, p1, null, Duration.ofDays(30)), // estorno
                fact(FactKind.PROMISE, "BROKEN", CASE, CREDITOR, PORTFOLIO, p2, null, Duration.ofDays(20)),
                fact(FactKind.PROMISE, "KEPT", CASE, CREDITOR, PORTFOLIO, p3, null, Duration.ofDays(10)),
                fact(FactKind.PROMISE, "BROKEN", OTHER_CASE, CREDITOR, PORTFOLIO, UUID.randomUUID(), null,
                        Duration.ofDays(10)));
        FeatureVector v = FeatureCalculator.compute(inputs("OPEN", null), facts, AT, null);
        assertThat(v.get(Feature.PROMISES_KEPT)).isEqualTo(1.0);
        assertThat(v.get(Feature.PROMISES_BROKEN)).isEqualTo(1.0);
    }

    @Test
    void agreementsAreCountedInTheCreditorRelationship() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        List<CaseFact> facts = List.of(
                fact(FactKind.AGREEMENT, "FORMALIZED", OTHER_CASE, CREDITOR, OTHER_PORTFOLIO, a1, null, Duration.ofDays(300)),
                fact(FactKind.AGREEMENT, "BROKEN", OTHER_CASE, CREDITOR, OTHER_PORTFOLIO, a1, null, Duration.ofDays(250)),
                fact(FactKind.AGREEMENT, "FORMALIZED", CASE, CREDITOR, PORTFOLIO, a2, null, Duration.ofDays(30)),
                fact(FactKind.AGREEMENT, "FORMALIZED", null, OTHER_CREDITOR, null, UUID.randomUUID(), null,
                        Duration.ofDays(30)));
        FeatureVector v = FeatureCalculator.compute(inputs("OPEN", null), facts, AT, null);
        assertThat(v.get(Feature.PRIOR_AGREEMENTS)).isEqualTo(2.0);
        assertThat(v.get(Feature.BROKEN_AGREEMENTS)).isEqualTo(1.0);
    }

    @Test
    void paymentsAreNetOfReversalsAndScopedToThePortfolio() {
        UUID pay1 = UUID.randomUUID();
        UUID pay2 = UUID.randomUUID();
        UUID pay3 = UUID.randomUUID();
        List<CaseFact> facts = List.of(
                fact(FactKind.PAYMENT, "PIX", null, CREDITOR, PORTFOLIO, pay1, null, Duration.ofDays(50)),
                fact(FactKind.PAYMENT, "PIX", null, CREDITOR, PORTFOLIO, pay2, null, Duration.ofDays(8)),
                fact(FactKind.PAYMENT_REVERSAL, "CHARGEBACK", null, CREDITOR, PORTFOLIO, pay2, null, Duration.ofDays(2)),
                fact(FactKind.PAYMENT, "PIX", null, CREDITOR, OTHER_PORTFOLIO, pay3, null, Duration.ofDays(1)),
                fact(FactKind.PAYMENT, "PIX", null, CREDITOR, PORTFOLIO, UUID.randomUUID(), null, Duration.ofDays(400)));
        FeatureVector v = FeatureCalculator.compute(inputs("OPEN", null), facts, AT, null);
        assertThat(v.get(Feature.PARTIAL_PAYMENTS_365D)).isEqualTo(1.0);
        assertThat(v.get(Feature.DAYS_SINCE_LAST_PAYMENT)).isEqualTo(50.0);
    }

    @Test
    void factsKnownAfterTheReferenceInstantNeverLeak() {
        UUID pay = UUID.randomUUID();
        List<CaseFact> facts = List.of(
                fact(FactKind.EFFECTIVE_CONTACT, null, CASE, CREDITOR, PORTFOLIO, null, "PHONE", Duration.ofDays(-1)),
                fact(FactKind.PAYMENT, "PIX", null, CREDITOR, PORTFOLIO, pay, null, Duration.ofHours(-2)),
                new CaseFact(FactKind.DEBTOR_RESPONSE, null, CASE, UUID.randomUUID(), CREDITOR, PORTFOLIO, null, "SMS",
                        null, AT.minusSeconds(60), AT.minusSeconds(60)));
        FeatureVector v = FeatureCalculator.compute(inputs("OPEN", null), facts, AT, null);
        assertThat(v.get(Feature.EFFECTIVE_CONTACTS_90D)).isZero();
        assertThat(v.get(Feature.PARTIAL_PAYMENTS_365D)).isZero();
        assertThat(v.get(Feature.DAYS_SINCE_LAST_PAYMENT)).isNull();
        // fato de outro devedor nunca entra
        assertThat(v.get(Feature.RESPONSIVE_CHANNELS_90D)).isZero();
    }

    @Test
    void daysOverdueIsCapped() {
        CaseInputs old = new CaseInputs(CASE, DEBTOR, CREDITOR, PORTFOLIO, null, "OPEN", null, 99_999, BigDecimal.ONE, 1,
                AT, null);
        assertThat(FeatureCalculator.compute(old, List.of(), AT, null).get(Feature.DAYS_OVERDUE))
                .isEqualTo((double) FeatureCalculator.MAX_DAYS_OVERDUE);
    }
}
