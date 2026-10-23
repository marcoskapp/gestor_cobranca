package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementOrigin;
import br.com.gestorcobranca.negotiation.domain.service.AttributionRules.Facts;
import br.com.gestorcobranca.negotiation.domain.service.AttributionRules.Participant;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionRulesTest {

    private final UUID human = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID assistant = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @Test
    void humanAndCopilot() {
        assertThat(AttributionRules.resolve(new Facts("USER", human, "USER", human, false, null, false, null)).origin())
                .isEqualTo(AgreementOrigin.HUMAN);
        var copilot = AttributionRules.resolve(new Facts("USER", human, "USER", other, true, null, false, approver));
        assertThat(copilot.origin()).isEqualTo(AgreementOrigin.HUMAN_COPILOT);
        assertThat(copilot.participants()).containsExactly(new Participant("USER", human, "CLOSER"),
                new Participant("USER", other, "ORIGINATOR"), new Participant("USER", approver, "APPROVER"));
    }

    @Test
    void handoffConfirmedWhenTheFormalizedTermsAreTheSuggestionOtherwiseNegotiated() {
        var confirmed = AttributionRules.resolve(new Facts("USER", human, "USER", human, false, assistant, true, null));
        assertThat(confirmed.origin()).isEqualTo(AgreementOrigin.ASSISTANT_HANDOFF_CONFIRMED);
        assertThat(confirmed.participants()).contains(new Participant("ASSISTANT", assistant, "PRE_NEGOTIATOR"));
        assertThat(AttributionRules.resolve(new Facts("USER", human, "USER", human, false, assistant, false, null)).origin())
                .isEqualTo(AgreementOrigin.ASSISTANT_HANDOFF_NEGOTIATED);
        // proposta pronta do assistente (sem poder formalizar) confirmada pelo humano
        var ready = AttributionRules.resolve(new Facts("USER", human, "ASSISTANT", assistant, false, null, false, null));
        assertThat(ready.origin()).isEqualTo(AgreementOrigin.ASSISTANT_HANDOFF_CONFIRMED);
        assertThat(ready.preNegotiatorId()).isEqualTo(assistant);
    }

    @Test
    void assistantPortalAndCampaign() {
        assertThat(AttributionRules.resolve(new Facts("ASSISTANT", assistant, "ASSISTANT", assistant, false, null, false,
                null)).origin()).isEqualTo(AgreementOrigin.ASSISTANT_AUTONOMOUS);
        assertThat(AttributionRules.resolve(new Facts("DEBTOR", other, "USER", human, false, null, false, null)).origin())
                .isEqualTo(AgreementOrigin.PORTAL_SELF_SERVICE);
        assertThat(AttributionRules.resolve(new Facts("SYSTEM", other, "USER", human, false, null, false, null)).origin())
                .isEqualTo(AgreementOrigin.CAMPAIGN);
    }
}
