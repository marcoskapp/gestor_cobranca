package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalMatterTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final StageRequirements NONE = new StageRequirements(false, null, Set.of(), false, null, null);

    private static LegalMatter matter() {
        return new LegalMatter(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId(), Set.of(Ids.newId()), MatterOrigin.RULE,
                Ids.newId(), null, Money.of("1000.00"), Set.of(), NOW);
    }

    private static StageRequirements viable() {
        return new StageRequirements(true, true, Set.of(), false, null, null);
    }

    @Test
    void fullJudicialPathWithSettlementBreakAndClosure() {
        LegalMatter m = matter();
        assertThat(m.getStage()).isEqualTo(LegalStage.ELIGIBLE);
        m.moveTo(LegalStage.UNDER_REVIEW, NONE, "aceite", NOW);
        m.moveTo(LegalStage.AWAITING_AUTHORIZATION, viable(), "viável", NOW);
        StageRequirements authorized = new StageRequirements(true, true, Set.of(LegalRoute.EXECUTION, LegalRoute.PROTEST),
                true, null, null);
        m.moveTo(LegalStage.EXTRAJUDICIAL, authorized, "protesto", NOW);
        m.moveTo(LegalStage.FILED, authorized, "ajuizado", NOW);
        m.moveTo(LegalStage.IN_PROGRESS, authorized, "citado", NOW);
        m.moveTo(LegalStage.ENFORCEMENT, authorized, "penhora", NOW);
        UUID agreement = Ids.newId();
        m.moveTo(LegalStage.SETTLED_IN_COURT, new StageRequirements(true, true, Set.of(), true, agreement, null), "acordo", NOW);
        assertThat(m.getResumeStage()).isEqualTo(LegalStage.ENFORCEMENT);
        assertThat(m.getAgreementId()).isEqualTo(agreement);

        assertThat(m.resumeAfterAgreement("quebra", NOW)).isEqualTo(LegalStage.SETTLED_IN_COURT);
        assertThat(m.getStage()).isEqualTo(LegalStage.ENFORCEMENT);
        assertThat(m.getAgreementId()).isNull();

        m.moveTo(LegalStage.CLOSED, new StageRequirements(true, true, Set.of(), true, null, ClosureReason.PAID), "pago", NOW);
        assertThat(m.getStage().isTerminal()).isTrue();
        assertThat(m.getClosureReason()).isEqualTo(ClosureReason.PAID);
        assertThat(m.getClosedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> m.moveTo(LegalStage.UNDER_REVIEW, NONE, "reabrir", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.transition_invalid");
    }

    @Test
    void preconditionsAreEnforced() {
        LegalMatter m = matter();
        assertThatThrownBy(() -> m.moveTo(LegalStage.FILED, NONE, "pular", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.transition_invalid");
        assertThatThrownBy(() -> m.moveTo(LegalStage.UNDER_REVIEW, NONE, " ", NOW))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_matter.reason_required");
        m.moveTo(LegalStage.UNDER_REVIEW, NONE, "aceite", NOW);
        assertThatThrownBy(() -> m.moveTo(LegalStage.AWAITING_AUTHORIZATION, NONE, "sem análise", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.viability_required");
        assertThatThrownBy(() -> m.moveTo(LegalStage.AWAITING_AUTHORIZATION,
                new StageRequirements(true, false, Set.of(), false, null, null), "inviável", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.viability_required");
        m.moveTo(LegalStage.AWAITING_AUTHORIZATION, viable(), "ok", NOW);
        // só protesto autorizado: ajuizamento recusado
        StageRequirements protestOnly = new StageRequirements(true, true, Set.of(LegalRoute.PROTEST), true, null, null);
        assertThatThrownBy(() -> m.moveTo(LegalStage.FILED, protestOnly, "ajuizar", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.authorization_required");
        StageRequirements judicialNoLawsuit = new StageRequirements(true, true, Set.of(LegalRoute.MONITORY_ACTION), false,
                null, null);
        assertThatThrownBy(() -> m.moveTo(LegalStage.FILED, judicialNoLawsuit, "ajuizar", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.lawsuit_required");
        assertThatThrownBy(() -> m.moveTo(LegalStage.EXTRAJUDICIAL, judicialNoLawsuit, "protestar", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.authorization_required");
        assertThatThrownBy(() -> m.moveTo(LegalStage.CLOSED, NONE, "fim", NOW))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_matter.closure_reason_required");
    }

    @Test
    void notViableCanBeReopenedOrReturned() {
        LegalMatter m = matter();
        m.moveTo(LegalStage.UNDER_REVIEW, NONE, "aceite", NOW);
        assertThatThrownBy(() -> m.moveTo(LegalStage.NOT_VIABLE, NONE, "sem análise", NOW))
                .isInstanceOf(ConflictException.class);
        m.moveTo(LegalStage.NOT_VIABLE, new StageRequirements(true, false, Set.of(), false, null, null), "sem bens", NOW);
        assertThat(m.getStage().isTerminal()).isFalse();
        m.moveTo(LegalStage.UNDER_REVIEW, NONE, "bem localizado", NOW);
        m.moveTo(LegalStage.RETURNED_TO_CREDITOR, NONE, "credor assume", NOW);
        assertThat(m.getStage().isTerminal()).isTrue();
        assertThatThrownBy(() -> m.assignInternal(Ids.newId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void manualReferralRequiresReasonAndAssignmentSwitchesResponsible() {
        assertThatThrownBy(() -> new LegalMatter(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId(), Set.of(Ids.newId()),
                MatterOrigin.MANUAL, null, " ", Money.of("10.00"), Set.of(), NOW))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_matter.reason_required");
        LegalMatter m = matter();
        UUID user = Ids.newId();
        assertThat(m.assignInternal(user)).isTrue();
        assertThat(m.assignInternal(user)).isFalse();
        UUID firm = Ids.newId();
        assertThat(m.assignLawFirm(firm, null)).isTrue();
        assertThat(m.getResponsibleType()).isEqualTo(ResponsibleType.LAW_FIRM);
        assertThat(m.getLawFirmId()).isEqualTo(firm);
        assertThat(m.getResponsibleUserId()).isNull();
    }

    @Test
    void collectionHoldStageFollowsConfiguration() {
        assertThat(CollectionHoldStage.AUTHORIZATION.holds(LegalStage.UNDER_REVIEW)).isFalse();
        assertThat(CollectionHoldStage.AUTHORIZATION.holds(LegalStage.EXTRAJUDICIAL)).isTrue();
        assertThat(CollectionHoldStage.AUTHORIZATION.holds(LegalStage.SETTLED_IN_COURT)).isTrue();
        assertThat(CollectionHoldStage.REVIEW.holds(LegalStage.UNDER_REVIEW)).isTrue();
        assertThat(CollectionHoldStage.REVIEW.holds(LegalStage.NOT_VIABLE)).isFalse();
        assertThat(CollectionHoldStage.REVIEW.holds(LegalStage.ELIGIBLE)).isFalse();
    }
}
