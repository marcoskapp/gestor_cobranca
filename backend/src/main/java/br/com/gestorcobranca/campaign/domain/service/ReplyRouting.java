package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;

/**
 * Transição da régua pela resposta do devedor (RB07): vale a regra da última etapa executada (a
 * mensagem que o devedor respondeu); sem etapa executada ou sem regra própria, a padrão da régua.
 */
public final class ReplyRouting {

    private ReplyRouting() {
    }

    /** @param targetStepCode etapa de destino em {@code GOTO_STEP} */
    public record Route(ReplyTransition transition, String targetStepCode) {
    }

    public static Route route(CadenceDefinition definition, String lastStepCode) {
        CadenceStep last = lastStepCode == null ? null : definition.step(lastStepCode).orElse(null);
        if (last != null && last.onReply() != null) {
            return new Route(last.onReply(), last.onReplyStepCode());
        }
        return new Route(definition.defaultOnReply(), definition.defaultOnReplyStepCode());
    }
}
