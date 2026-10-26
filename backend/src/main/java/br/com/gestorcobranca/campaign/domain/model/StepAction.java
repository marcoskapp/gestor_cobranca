package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ação de uma etapa.
 *
 * @param type          ação
 * @param channel       WHATSAPP, EMAIL ou SMS (mensagem e oferta)
 * @param templateCode  código do template publicado no communication (do credor ou da assessoria)
 * @param variables     variável do template → origem ({@link VariableSource}, ou {@code LITERAL:texto})
 * @param taskType      CALLBACK ou FOLLOW_UP (tarefa)
 * @param taskDueInMinutes prazo da tarefa a partir da execução
 * @param taskNote      observação da tarefa (sem dado pessoal; guardada criptografada pelo collection)
 * @param teamId        equipe de destino (mover para a fila); nulo = devolver à fila do roteamento
 * @param offer         parâmetros da oferta (ação {@code OFFER})
 */
public record StepAction(ActionType type, String channel, String templateCode, Map<String, String> variables,
                         String taskType, Integer taskDueInMinutes, String taskNote, UUID teamId, OfferSpec offer) {

    public static final Set<String> MESSAGE_CHANNELS = Set.of("WHATSAPP", "EMAIL", "SMS");
    public static final Set<String> TASK_TYPES = Set.of("CALLBACK", "FOLLOW_UP");
    private static final Pattern TEMPLATE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
    private static final Pattern VARIABLE = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final int MAX_DUE_MINUTES = 30 * 24 * 60;

    public StepAction {
        channel = channel == null || channel.isBlank() ? null : channel.trim().toUpperCase(Locale.ROOT);
        templateCode = templateCode == null || templateCode.isBlank() ? null : templateCode.trim();
        variables = variables == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(variables));
        taskType = taskType == null || taskType.isBlank() ? null : taskType.trim().toUpperCase(Locale.ROOT);
    }

    /** Origens usadas nas variáveis da etapa. */
    public Set<VariableSource> sources() {
        return variables.values().stream().map(StepAction::source).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** A etapa precisa da próxima parcela do acordo em vigor do caso. */
    public boolean needsInstallment() {
        return sendsMessage() && sources().stream().anyMatch(VariableSource::requiresInstallment);
    }

    /** A etapa gera um link de uso único do portal no envio. */
    public boolean needsPortalLink() {
        return sendsMessage() && sources().contains(VariableSource.PORTAL_LINK);
    }

    public boolean sendsMessage() {
        return type == ActionType.SEND_MESSAGE || type == ActionType.OFFER;
    }

    /** Ações que encerram a inscrição: o atendimento passa ao assistente ou aos humanos. */
    public boolean terminal() {
        return type == ActionType.HAND_TO_ASSISTANT || type == ActionType.MOVE_TO_QUEUE;
    }

    public StepAction validate() {
        Guard.require(type != null, "cadence.action_required", "Informe a ação da etapa");
        switch (type) {
            case SEND_MESSAGE, OFFER -> {
                Guard.require(channel != null && MESSAGE_CHANNELS.contains(channel), "cadence.channel_invalid",
                        "Canal da mensagem: WHATSAPP, EMAIL ou SMS");
                Guard.require(templateCode != null && TEMPLATE_CODE.matcher(templateCode).matches(),
                        "cadence.template_invalid", "Informe o código do template");
                variables.forEach(StepAction::validateVariable);
                Guard.require(!sources().contains(VariableSource.PORTAL_LINK_HOURS)
                                || sources().contains(VariableSource.PORTAL_LINK), "cadence.portal_hours_without_link",
                        "A validade do link do portal só vai junto com o link do portal na mesma mensagem");
                if (type == ActionType.OFFER) {
                    Guard.require(offer != null, "cadence.offer_required", "Informe os parâmetros da oferta");
                    offer.validate();
                } else {
                    Guard.require(variables.values().stream().map(StepAction::source)
                                    .noneMatch(VariableSource::requiresOffer), "cadence.offer_variable_without_offer",
                            "Variáveis de oferta só em etapas de oferta");
                }
            }
            case CREATE_TASK -> {
                Guard.require(taskType != null && TASK_TYPES.contains(taskType), "cadence.task_type_invalid",
                        "Tipo de tarefa: CALLBACK ou FOLLOW_UP");
                Guard.require(taskDueInMinutes != null && taskDueInMinutes >= 0 && taskDueInMinutes <= MAX_DUE_MINUTES,
                        "cadence.task_due_invalid", "Prazo da tarefa entre 0 e 30 dias");
                Guard.require(taskNote == null || taskNote.length() <= 500, "cadence.task_note_invalid",
                        "Observação com até 500 caracteres");
            }
            case HAND_TO_ASSISTANT, MOVE_TO_QUEUE -> {
                // sem parâmetros obrigatórios
            }
        }
        return this;
    }

    private static void validateVariable(String name, String source) {
        Guard.require(VARIABLE.matcher(name).matches(), "cadence.variable_invalid", "Nome de variável inválido: " + name);
        VariableSource s = source(source);
        if (s == VariableSource.LITERAL) {
            String literal = literal(source);
            Guard.require(!literal.isBlank() && literal.length() <= 200, "cadence.variable_literal_invalid",
                    "Texto fixo de variável com 1 a 200 caracteres");
        }
    }

    /** Origem de uma variável ({@code OPEN_BALANCE}, {@code LITERAL:texto}). */
    public static VariableSource source(String spec) {
        Guard.require(spec != null && !spec.isBlank(), "cadence.variable_source_invalid", "Origem de variável obrigatória");
        String head = spec.contains(":") ? spec.substring(0, spec.indexOf(':')) : spec;
        try {
            return VariableSource.valueOf(head.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new br.com.gestorcobranca.shared.domain.BusinessRuleException("cadence.variable_source_invalid",
                    "Origem de variável desconhecida: " + head);
        }
    }

    public static String literal(String spec) {
        int colon = spec.indexOf(':');
        return colon < 0 ? "" : spec.substring(colon + 1);
    }
}
