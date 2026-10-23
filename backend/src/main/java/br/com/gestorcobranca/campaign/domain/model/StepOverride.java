package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Locale;

/**
 * Alteração de uma etapa numa variante do teste A/B: template, canal, horário (janela) ou oferta.
 * Campos nulos mantêm o valor da régua.
 */
public record StepOverride(String stepCode, String channel, String templateCode, SendWindow window, OfferSpec offer) {

    public StepOverride {
        channel = channel == null || channel.isBlank() ? null : channel.trim().toUpperCase(Locale.ROOT);
        templateCode = templateCode == null || templateCode.isBlank() ? null : templateCode.trim();
    }

    /** Aplica a alteração à etapa (valida a combinação resultante). */
    public CadenceStep applyTo(CadenceStep step) {
        StepAction a = step.action();
        StepAction action = new StepAction(a.type(), channel != null ? channel : a.channel(),
                templateCode != null ? templateCode : a.templateCode(), a.variables(), a.taskType(),
                a.taskDueInMinutes(), a.taskNote(), a.teamId(), offer != null ? offer : a.offer());
        Guard.require(channel == null && templateCode == null || a.sendsMessage(), "experiment.override_invalid",
                "Template/canal só em etapas de mensagem: " + step.code());
        Guard.require(offer == null || a.type() == ActionType.OFFER, "experiment.override_invalid",
                "Oferta só em etapas de oferta: " + step.code());
        return new CadenceStep(step.code(), step.name(), step.waitMinutes(), window != null ? window : step.window(),
                step.condition(), action.validate(), step.onReply(), step.onReplyStepCode());
    }
}
