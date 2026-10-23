package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static br.com.gestorcobranca.campaign.domain.CampaignFixtures.message;
import static br.com.gestorcobranca.campaign.domain.CampaignFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transições da régua: ordem das etapas, fim e redirecionamento por resposta (RB07). */
class ReplyRoutingTest {

    private final CadenceDefinition cadence = new CadenceDefinition(ReplyTransition.MOVE_TO_QUEUE, null, null, List.of(
            message("D0", 0, null, null),
            message("D3", 3 * 1440, ReplyTransition.GOTO_STEP, "OFERTA"),
            message("OFERTA", 60, ReplyTransition.HAND_TO_ASSISTANT, null),
            task("LIGAR"))).validate();

    @Test
    void stepsFollowDeclaredOrderUntilTheEnd() {
        assertThat(cadence.first().code()).isEqualTo("D0");
        assertThat(cadence.after("D0")).map(s -> s.code()).contains("D3");
        assertThat(cadence.after("OFERTA")).map(s -> s.code()).contains("LIGAR");
        assertThat(cadence.after("LIGAR")).isEmpty();
        assertThat(cadence.messageChannels()).containsExactly("WHATSAPP");
    }

    @Test
    void replyUsesTheRuleOfTheLastExecutedStepOrTheCadenceDefault() {
        assertThat(ReplyRouting.route(cadence, "D3")).isEqualTo(new ReplyRouting.Route(ReplyTransition.GOTO_STEP, "OFERTA"));
        assertThat(ReplyRouting.route(cadence, "OFERTA").transition()).isEqualTo(ReplyTransition.HAND_TO_ASSISTANT);
        assertThat(ReplyRouting.route(cadence, "D0").transition()).isEqualTo(ReplyTransition.MOVE_TO_QUEUE);
        assertThat(ReplyRouting.route(cadence, null).transition()).isEqualTo(ReplyTransition.MOVE_TO_QUEUE);
    }

    @Test
    void defaultTransitionIsStop() {
        CadenceDefinition plain = new CadenceDefinition(null, null, null, List.of(message("A", 0, null, null)));
        assertThat(ReplyRouting.route(plain, "A").transition()).isEqualTo(ReplyTransition.STOP);
    }

    @Test
    void invalidDefinitionsAreRejected() {
        assertThatThrownBy(() -> new CadenceDefinition(null, null, null, List.of()).validate())
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("1 a 30");
        assertThatThrownBy(() -> new CadenceDefinition(null, null, null, List.of(message("A", 0, null, null),
                message("A", 0, null, null))).validate()).hasMessageContaining("repetido");
        assertThatThrownBy(() -> new CadenceDefinition(null, null, null, List.of(
                message("A", 0, ReplyTransition.GOTO_STEP, "Z"))).validate()).hasMessageContaining("inexistente");
        assertThatThrownBy(() -> new CadenceDefinition(ReplyTransition.GOTO_STEP, "Z", null,
                List.of(message("A", 0, null, null))).validate()).hasMessageContaining("padrão inexistente");
        assertThatThrownBy(() -> new CadenceDefinition(null, null, null, List.of(message("A", -1, null, null))).validate())
                .hasMessageContaining("Espera");
    }
}
