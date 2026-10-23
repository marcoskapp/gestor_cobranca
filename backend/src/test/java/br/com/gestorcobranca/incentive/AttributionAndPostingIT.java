package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.incentive.application.port.in.AttributionQueries;
import br.com.gestorcobranca.incentive.application.port.in.AttributionQueries.AttributionView;
import br.com.gestorcobranca.incentive.application.port.in.AttributionQueries.CreditView;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.InstallmentStatusChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Atribuição com participação da IA, portal com janela de influência, renegociação incremental
 * e pagamento que chega antes da atribuição.
 */
class AttributionAndPostingIT extends IncentiveTestSupport {

    @Autowired
    AttributionQueries attributions;

    @BeforeEach
    void setUp() {
        provisionTenant("attr");
    }

    @Test
    void assistantParticipationWeightsFollowThePlan() {
        UUID confirmed = Ids.newId();
        UUID autonomous = Ids.newId();
        UUID copilot = Ids.newId();
        publishAndAwait(attribution(confirmed, Ids.newId(), "ASSISTANT_HANDOFF_CONFIRMED",
                List.of(assistant("PRE_NEGOTIATOR"), closer(operatorX)), "3000.00", 45, null, at(2026, 8, 3)),
                "incentive.attribution-recorded");
        publishAndAwait(attribution(autonomous, Ids.newId(), "ASSISTANT_AUTONOMOUS", List.of(assistant("CLOSER")),
                "3000.00", 45, null, at(2026, 8, 3)), "incentive.attribution-recorded");
        publishAndAwait(attribution(copilot, Ids.newId(), "HUMAN_COPILOT", List.of(closer(operatorY)), "3000.00", 45, null,
                at(2026, 8, 3)), "incentive.attribution-recorded");
        UUID p1 = Ids.newId();
        UUID p2 = Ids.newId();
        UUID p3 = Ids.newId();
        publishAndAwait(payment(p1, confirmed, "1000.00", at(2026, 8, 5)), "incentive.payment-confirmed");
        publishAndAwait(payment(p2, autonomous, "1000.00", at(2026, 8, 5)), "incentive.payment-confirmed");
        publishAndAwait(payment(p3, copilot, "1000.00", at(2026, 8, 5)), "incentive.payment-confirmed");

        // handoff só confirmado: 30% para o closer
        assertThat(credited(p1, "USER")).isEqualByComparingTo("300.00");
        // autônomo do assistente: nenhum crédito individual, só o pool da equipe
        assertThat(count("select count(*) from incentive.commission_ledger_entry where payment_id = ? "
                + "and beneficiary_type = 'USER'", p2)).isZero();
        assertThat(credited(p2, "TEAM")).isEqualByComparingTo("1000.00");
        // copiloto: 100% para quem fechou
        assertThat(credited(p3, "USER")).isEqualByComparingTo("1000.00");

        AttributionView view = as(manager, DefaultRoles.GESTOR, () -> attributions.byAgreement(confirmed));
        assertThat(view.participants()).hasSize(2);
        assertThat(view.credits()).extracting(CreditView::beneficiaryType).containsExactlyInAnyOrder("USER", "TEAM");
        // fora do escopo de credores: 404
        assertThatThrownBy(() -> asScoped(manager, DefaultRoles.GESTOR, Set.of(creditorB),
                () -> attributions.byAgreement(confirmed))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void portalAgreementCreditsTheHumanWithEffectiveContactInsideTheInfluenceWindow() {
        UUID caseWithContact = Ids.newId();
        UUID caseWithoutRecentContact = Ids.newId();
        // contato efetivo de Y 3 dias antes (dentro da janela de 7 dias) e de X 20 dias antes (fora)
        publishAndAwait(interaction(caseWithContact, operatorY, true, at(2026, 8, 7)), "incentive.interaction-recorded");
        publishAndAwait(interaction(caseWithoutRecentContact, operatorX, true, at(2026, 7, 21)),
                "incentive.interaction-recorded");
        UUID a1 = Ids.newId();
        UUID a2 = Ids.newId();
        publishAndAwait(attribution(a1, caseWithContact, "PORTAL_SELF_SERVICE", List.of(new
                br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded.Participant("DEBTOR",
                Ids.newId(), "CLOSER")), "2000.00", 10, null, at(2026, 8, 10)), "incentive.attribution-recorded");
        publishAndAwait(attribution(a2, caseWithoutRecentContact, "CAMPAIGN", List.of(), "2000.00", 10, null,
                at(2026, 8, 10)), "incentive.attribution-recorded");
        UUID p1 = Ids.newId();
        UUID p2 = Ids.newId();
        publishAndAwait(payment(p1, a1, "800.00", at(2026, 8, 11)), "incentive.payment-confirmed");
        publishAndAwait(payment(p2, a2, "800.00", at(2026, 8, 11)), "incentive.payment-confirmed");

        // 50% do pagamento para o influenciador Y
        assertThat(count("select count(*) from incentive.commission_ledger_entry where payment_id = ? "
                + "and beneficiary_type = 'USER' and beneficiary_id = ? and role = 'INFLUENCER'", p1, operatorY))
                .isEqualTo(1);
        assertThat(credited(p1, "USER")).isEqualByComparingTo("400.00");
        // sem contato humano na janela: nenhum crédito individual
        assertThat(count("select count(*) from incentive.commission_ledger_entry where payment_id = ? "
                + "and beneficiary_type = 'USER'", p2)).isZero();
    }

    @Test
    void ownRenegotiationCreditsOnlyTheIncrementalValueWithReducedWeight() {
        UUID original = Ids.newId();
        UUID renegotiation = Ids.newId();
        UUID debtCase = Ids.newId();
        publishAndAwait(attribution(original, debtCase, "HUMAN", List.of(closer(operatorX)), "10000.00", 100, null,
                at(2026, 8, 3)), "incentive.attribution-recorded");
        publishAndAwait(payment(Ids.newId(), original, "3000.00", at(2026, 8, 4)), "incentive.payment-confirmed");
        // quebrou e foi renegociado pelo mesmo negociador por 8.000: 7.000 ainda estavam em aberto
        publishAndAwait(attribution(renegotiation, debtCase, "HUMAN", List.of(closer(operatorX)), "8000.00", 130, original,
                at(2026, 8, 20)), "incentive.attribution-recorded");
        AttributionView view = as(manager, DefaultRoles.GESTOR, () -> attributions.byAgreement(renegotiation));
        assertThat(view.incrementalAmount()).isEqualByComparingTo("1000.00");
        assertThat(view.credits().stream().filter(c -> c.beneficiaryType().equals("USER")).findFirst().orElseThrow()
                .renegotiationFactor()).isEqualByComparingTo("50");
        UUID payment = Ids.newId();
        publishAndAwait(payment(payment, renegotiation, "2000.00", at(2026, 8, 25)), "incentive.payment-confirmed");
        assertThat(credited(payment, "USER")).isEqualByComparingTo("1000.00");
        // valor negociado do negociador soma 10.000 + 1.000 (incremental), não 18.000
        assertThat(decimal("select sum(negotiated_amount) from incentive.performance_fact where user_id = ?", operatorX))
                .isEqualByComparingTo("11000.00");
    }

    @Test
    void paymentArrivingBeforeTheAttributionIsPostedWhenTheAttributionArrives() {
        UUID agreement = Ids.newId();
        UUID payment = Ids.newId();
        publishAndAwait(payment(payment, agreement, "1500.00", at(2026, 8, 5)), "incentive.payment-confirmed");
        assertThat(count("select count(*) from incentive.commission_ledger_entry where payment_id = ?", payment)).isZero();
        assertThat(count("select count(*) from incentive.payment_fact where payment_id = ? and posted_at is null",
                payment)).isEqualTo(1);
        // a 1ª parcela também chega antes: o indicador é contado quando a atribuição chega
        publishAndAwait(new InstallmentStatusChanged(Ids.newId(), tenant, at(2026, 8, 5), Ids.newId(), agreement,
                Ids.newId(), creditorA, 1, LocalDate.of(2026, 8, 5), new BigDecimal("1500.00"), new BigDecimal("1500.00"),
                "OPEN", "PAID"), "incentive.installment-status");
        publishAndAwait(attribution(agreement, Ids.newId(), "HUMAN", List.of(closer(operatorX)), "6000.00", 20, null,
                at(2026, 8, 2)), "incentive.attribution-recorded");
        assertThat(credited(payment, "USER")).isEqualByComparingTo("1500.00");
        assertThat(count("select count(*) from incentive.payment_fact where payment_id = ? and posted_at is not null",
                payment)).isEqualTo(1);
        assertThat(count("select coalesce(sum(first_installments), 0) from incentive.performance_fact where user_id = ?",
                operatorX)).isEqualTo(1);
    }

    private BigDecimal credited(UUID paymentId, String beneficiaryType) {
        return decimal("select sum(credited_amount) from incentive.commission_ledger_entry where payment_id = ? "
                + "and beneficiary_type = ? and kind = 'ACCRUAL'", paymentId, beneficiaryType);
    }

    private InteractionRecorded interaction(UUID caseId, UUID operator, boolean effective, java.time.Instant at) {
        return new InteractionRecorded(Ids.newId(), tenant, at, Ids.newId(), caseId, Ids.newId(), creditorA, "PHONE",
                "CPC", effective, operator, "OUTBOUND", portfolio);
    }
}
