package br.com.gestorcobranca.campaign.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Origens de parcela do acordo e link do portal nas etapas de mensagem. */
class StepActionTest {

    private static StepAction message(Map<String, String> variables) {
        return new StepAction(ActionType.SEND_MESSAGE, "WHATSAPP", "ACORDO_LEMBRETE_PARCELA", variables, null, null, null,
                null, null);
    }

    @Test
    void installmentAndPortalSourcesAreAllowedInMessageSteps() {
        StepAction action = message(Map.of("valor", "NEXT_INSTALLMENT_AMOUNT", "vencimento", "NEXT_INSTALLMENT_DUE_DATE",
                "link", "PORTAL_LINK", "horas", "PORTAL_LINK_HOURS")).validate();
        assertThat(action.needsInstallment()).isTrue();
        assertThat(action.needsPortalLink()).isTrue();
        StepAction plain = message(Map.of("nome", "DEBTOR_FIRST_NAME")).validate();
        assertThat(plain.needsInstallment()).isFalse();
        assertThat(plain.needsPortalLink()).isFalse();
    }

    @Test
    void portalLinkHoursOnlyTogetherWithTheLink() {
        assertThatThrownBy(() -> message(Map.of("horas", "PORTAL_LINK_HOURS")).validate())
                .hasFieldOrPropertyWithValue("code", "cadence.portal_hours_without_link");
    }

    @Test
    void sourcesNeverRequireAnOffer() {
        assertThat(VariableSource.NEXT_INSTALLMENT_AMOUNT.requiresOffer()).isFalse();
        assertThat(VariableSource.PORTAL_LINK.requiresOffer()).isFalse();
        assertThat(VariableSource.NEXT_INSTALLMENT_DUE_DATE.requiresInstallment()).isTrue();
        assertThat(VariableSource.PORTAL_LINK_HOURS.requiresPortalLink()).isTrue();
    }
}
