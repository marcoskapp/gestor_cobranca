package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.regex.Pattern;

/**
 * Etapa da régua.
 *
 * @param code            identificador estável na régua (chave idempotente com a inscrição)
 * @param waitMinutes     espera desde a etapa anterior (ou desde a inscrição, na primeira)
 * @param window          janela de horário da etapa (nula = qualquer horário; o porteiro vale sempre)
 * @param condition       condição para executar (nula = sempre)
 * @param onReply         o que a resposta do devedor a esta etapa faz (nulo = padrão da régua)
 * @param onReplyStepCode etapa de destino quando {@code onReply = GOTO_STEP}
 */
@org.springframework.modulith.NamedInterface("usecases")
public record CadenceStep(String code, String name, Integer waitMinutes, SendWindow window, StepCondition condition,
                          StepAction action, ReplyTransition onReply, String onReplyStepCode) {

    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    public static final int MAX_WAIT_MINUTES = 60 * 24 * 60;

    public CadenceStep {
        waitMinutes = waitMinutes == null ? 0 : waitMinutes;
        window = window == null ? SendWindow.always() : window;
        condition = condition == null ? StepCondition.none() : condition;
    }

    CadenceStep validate() {
        Guard.require(code != null && CODE.matcher(code).matches(), "cadence.step_code_invalid",
                "Código de etapa: letras maiúsculas, dígitos e _ (até 40)");
        Guard.require(name == null || name.length() <= 120, "cadence.step_name_invalid", "Nome com até 120 caracteres");
        Guard.require(waitMinutes >= 0 && waitMinutes <= MAX_WAIT_MINUTES, "cadence.wait_invalid",
                "Espera entre 0 e 60 dias");
        Guard.require(action != null, "cadence.action_required", "Informe a ação da etapa " + code);
        window.validate();
        condition.validate();
        action.validate();
        Guard.require(onReply != ReplyTransition.GOTO_STEP || onReplyStepCode != null, "cadence.reply_target_required",
                "Informe a etapa de destino da resposta");
        return this;
    }
}
