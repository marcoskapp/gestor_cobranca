package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Definição de uma versão da régua: etapas ordenadas e a transição padrão por resposta (RB07).
 *
 * @param defaultOnReply       transição quando a etapa não define a própria (padrão {@code STOP})
 * @param defaultOnReplyStepCode destino de {@code GOTO_STEP} padrão
 * @param replyTeamId          equipe de destino de {@code MOVE_TO_QUEUE} por resposta (nula = fila do roteamento)
 */
public record CadenceDefinition(ReplyTransition defaultOnReply, String defaultOnReplyStepCode, UUID replyTeamId,
                                List<CadenceStep> steps) {

    public static final int MAX_STEPS = 30;

    public CadenceDefinition {
        defaultOnReply = defaultOnReply == null ? ReplyTransition.STOP : defaultOnReply;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public CadenceDefinition validate() {
        Guard.require(!steps.isEmpty() && steps.size() <= MAX_STEPS, "cadence.steps_invalid",
                "A régua precisa de 1 a %d etapas".formatted(MAX_STEPS));
        Set<String> codes = new HashSet<>();
        for (CadenceStep step : steps) {
            Guard.require(step != null, "cadence.steps_invalid", "Etapa vazia");
            step.validate();
            Guard.require(codes.add(step.code()), "cadence.step_code_duplicated", "Código de etapa repetido: " + step.code());
        }
        for (CadenceStep step : steps) {
            if (step.onReply() == ReplyTransition.GOTO_STEP) {
                Guard.require(codes.contains(step.onReplyStepCode()), "cadence.reply_target_invalid",
                        "Etapa de destino inexistente: " + step.onReplyStepCode());
            }
        }
        Guard.require(defaultOnReply != ReplyTransition.GOTO_STEP || codes.contains(defaultOnReplyStepCode),
                "cadence.reply_target_invalid", "Etapa de destino padrão inexistente");
        return this;
    }

    public CadenceStep first() {
        return steps.getFirst();
    }

    public Optional<CadenceStep> step(String code) {
        return steps.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    /** Etapa seguinte na ordem da régua (vazio = fim). */
    public Optional<CadenceStep> after(String code) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).code().equals(code)) {
                return i + 1 < steps.size() ? Optional.of(steps.get(i + 1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Canais de mensagem usados pela régua (pausa por restrição de contato). */
    public Set<String> messageChannels() {
        return steps.stream().map(CadenceStep::action).filter(StepAction::sendsMessage).map(StepAction::channel)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasAction(ActionType type) {
        return steps.stream().anyMatch(s -> s.action().type() == type);
    }
}
