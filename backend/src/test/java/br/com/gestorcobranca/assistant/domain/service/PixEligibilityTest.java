package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.IdentityStatus;
import br.com.gestorcobranca.assistant.domain.model.PixCriterion;
import br.com.gestorcobranca.assistant.domain.model.PixDecision;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.PixInConversation;
import br.com.gestorcobranca.assistant.domain.service.PixEligibility.Agreement;
import br.com.gestorcobranca.assistant.domain.service.PixEligibility.Facts;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cada critério de "conversa amigável e conciliação tranquila", avaliado no servidor. */
class PixEligibilityTest {

    private static final PixInConversation ON = new PixInConversation(true, null, null, null, null).normalized();
    private static final Agreement AGREEMENT = new Agreement("FORMALIZED", "ASSISTANT_AUTONOMOUS", "OPEN",
            new BigDecimal("400.00"));

    private static Facts friendly() {
        return new Facts(ON, AssistantMode.AUTONOMOUS, IdentityStatus.VERIFIED, AGREEMENT, true, Set.of(), false, false,
                true, 0);
    }

    private static PixEligibility.Evaluation eval(Facts f) {
        return PixEligibility.evaluate(f);
    }

    private static Facts with(Facts f, PixInConversation s, AssistantMode m, IdentityStatus i, Agreement a,
                              Boolean same, Set<String> risks, Boolean guardrail, Boolean dispute, Boolean explicit,
                              Integer today) {
        return new Facts(s == null ? f.settings() : s, m == null ? f.mode() : m, i == null ? f.identity() : i,
                a == null ? f.agreement() : a, same == null ? f.sameParties() : same,
                risks == null ? f.sessionRisks() : risks, guardrail == null ? f.guardrailInSession() : guardrail,
                dispute == null ? f.openDispute() : dispute, explicit == null ? f.explicitRequest() : explicit,
                today == null ? f.issuedToday() : today);
    }

    @Test
    void friendlyConversationWithFormalizedAgreementIssues() {
        var e = eval(friendly());
        assertThat(e.decision()).isEqualTo(PixDecision.ISSUED);
        assertThat(e.allowed()).isTrue();
        assertThat(e.checks()).hasSize(PixCriterion.values().length).allMatch(PixEligibility.Check::passed);
    }

    @Test
    void policyOffByDefault() {
        assertThat(PolicySettings.defaults(AssistantMode.AUTONOMOUS).pix().on()).isFalse();
        assertThat(PolicySettings.defaults(AssistantMode.AUTONOMOUS).pix().maxAmount()).isEqualByComparingTo("5000.00");
        var e = eval(with(friendly(), PixInConversation.disabled(), null, null, null, null, null, null, null, null, null));
        assertThat(e.denialCode()).contains("PIX_DISABLED");
    }

    @Test
    void pixRequiresIdentityVerificationInThePolicy() {
        assertThatThrownBy(() -> new PolicySettings(AssistantMode.AUTONOMOUS, null, null, null, null, null, null, null,
                null, null, false, null, null, null, null, null, null, null, null, null, null, null, null,
                new PixInConversation(true, null, null, null, null)).normalized())
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("identidade");
    }

    @Test
    void offModeIsNotAllowed() {
        assertThat(eval(with(friendly(), null, AssistantMode.OFF, null, null, null, null, null, null, null, null))
                .denialCode()).contains("MODE_NOT_ALLOWED");
    }

    @Test
    void identityMustBeVerifiedInTheSession() {
        assertThat(eval(with(friendly(), null, null, IdentityStatus.PENDING, null, null, null, null, null, null, null))
                .denialCode()).contains("IDENTITY_NOT_VERIFIED");
    }

    @Test
    void agreementMustBeFormalizedAndInForce() {
        assertThat(eval(new Facts(ON, AssistantMode.AUTONOMOUS, IdentityStatus.VERIFIED, null, true, Set.of(), false,
                false, true, 0)).denialCode()).contains("NO_FORMALIZED_AGREEMENT");
        assertThat(eval(with(friendly(), null, null, null, new Agreement("BROKEN", "HUMAN", "OPEN", BigDecimal.TEN), null,
                null, null, null, null, null)).denialCode()).contains("NO_FORMALIZED_AGREEMENT");
    }

    @Test
    void agreementOfAnotherDebtorOrCreditorIsRefused() {
        assertThat(eval(with(friendly(), null, null, null, null, false, null, null, null, null, null)).denialCode())
                .contains("OTHER_PARTY");
    }

    @Test
    void installmentMustHaveOpenBalance() {
        assertThat(eval(with(friendly(), null, null, null, new Agreement("IN_PAYMENT", "HUMAN", "PAID", BigDecimal.ZERO),
                null, null, null, null, null, null)).denialCode()).contains("INSTALLMENT_NOT_OPEN");
    }

    @Test
    void approvalModeOnlyForAgreementsFormalizedByHumans() {
        var assistantClosed = eval(with(friendly(), null, AssistantMode.AUTONOMOUS_WITH_APPROVAL, null, null, null, null,
                null, null, null, null));
        assertThat(assistantClosed.denialCode()).contains("NOT_FORMALIZED_BY_HUMAN");
        assertThat(assistantClosed.denial().orElseThrow().handsOff()).isTrue();
        assertThat(eval(with(friendly(), null, AssistantMode.AUTONOMOUS_WITH_APPROVAL, null,
                new Agreement("FORMALIZED", "ASSISTANT_HANDOFF_CONFIRMED", "OPEN", BigDecimal.TEN), null, null, null,
                null, null, null)).decision()).isEqualTo(PixDecision.ISSUED);
    }

    @Test
    void anyConversationRiskOrGuardrailInTheSessionBlocks() {
        var complaint = eval(with(friendly(), null, null, null, null, null, Set.of("COMPLAINT"), null, null, null, null));
        assertThat(complaint.denialCode()).contains("CONVERSATION_RISK");
        assertThat(complaint.denial().orElseThrow().handsOff()).isTrue();
        var guardrail = eval(with(friendly(), null, null, null, null, null, null, true, null, null, null));
        assertThat(guardrail.denialCode()).contains("CONVERSATION_RISK");
        assertThat(guardrail.checks()).filteredOn(c -> c.criterion() == PixCriterion.NO_CONVERSATION_RISK)
                .singleElement().extracting(PixEligibility.Check::detail).isEqualTo("GUARDRAIL");
    }

    @Test
    void openDisputeBlocks() {
        assertThat(eval(with(friendly(), null, null, null, null, null, null, null, true, null, null)).denialCode())
                .contains("OPEN_DISPUTE");
    }

    @Test
    void explicitRequestIsRequiredAndDoesNotTransfer() {
        var e = eval(with(friendly(), null, null, null, null, null, null, null, null, false, null));
        assertThat(e.denialCode()).contains("NO_EXPLICIT_REQUEST");
        assertThat(e.denial().orElseThrow().handsOff()).isFalse();
    }

    @Test
    void amountUpToThePolicyLimit() {
        assertThat(eval(with(friendly(), null, null, null, new Agreement("FORMALIZED", "HUMAN", "OPEN",
                new BigDecimal("5000.00")), null, null, null, null, null, null)).allowed()).isTrue();
        assertThat(eval(with(friendly(), null, null, null, new Agreement("FORMALIZED", "HUMAN", "OPEN",
                new BigDecimal("5000.01")), null, null, null, null, null, null)).denialCode()).contains("AMOUNT_ABOVE_LIMIT");
    }

    @Test
    void dailyLimitPerConversation() {
        assertThat(eval(with(friendly(), null, null, null, null, null, null, null, null, null, 2)).allowed()).isTrue();
        assertThat(eval(with(friendly(), null, null, null, null, null, null, null, null, null, 3)).denialCode())
                .contains("DAILY_LIMIT_REACHED");
    }

    @Test
    void draftModesOnlySuggestAndLeaveIdentityToTheOperator() {
        var e = eval(with(friendly(), null, AssistantMode.SHADOW, IdentityStatus.PENDING, null, null, null, null, null,
                null, null));
        assertThat(e.decision()).isEqualTo(PixDecision.SUGGESTED);
        assertThat(e.checks()).filteredOn(c -> c.criterion() == PixCriterion.IDENTITY_VERIFIED).singleElement()
                .extracting(PixEligibility.Check::detail).isEqualTo("OPERATOR_MEDIATED");
    }

    @Test
    void allCriteriaAreRecordedEvenWhenTheFirstFails() {
        var e = eval(new Facts(PixInConversation.disabled(), AssistantMode.OFF, IdentityStatus.PENDING, null, false,
                Set.of("ABUSIVE"), true, true, false, 9));
        assertThat(e.checks()).hasSize(PixCriterion.values().length).noneMatch(c -> c.criterion() == null);
        assertThat(e.denialCode()).contains("PIX_DISABLED");
        assertThat(e.checks().stream().filter(c -> !c.passed()).count()).isGreaterThan(8);
    }
}
