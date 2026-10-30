package br.com.gestorcobranca.campaign.domain.model;

/**
 * Condição para executar a etapa (avaliada no momento da execução; não atendida = etapa pulada com
 * {@code CONDITION_NOT_MET} e a régua segue).
 *
 * @param criteria                         filtros do segmento sobre a posição atual do caso (opcional)
 * @param requireNoReply                   só se o devedor ainda não respondeu nesta inscrição
 * @param requireNoEffectiveContact        só se não houve contato efetivo desde a inscrição
 * @param requirePreviousMessageUnread     só se a última mensagem enviada pela régua não foi lida
 */
@org.springframework.modulith.NamedInterface("usecases")
public record StepCondition(SegmentCriteria criteria, Boolean requireNoReply, Boolean requireNoEffectiveContact,
                            Boolean requirePreviousMessageUnread) {

    public StepCondition {
        requireNoReply = Boolean.TRUE.equals(requireNoReply);
        requireNoEffectiveContact = Boolean.TRUE.equals(requireNoEffectiveContact);
        requirePreviousMessageUnread = Boolean.TRUE.equals(requirePreviousMessageUnread);
    }

    public static StepCondition none() {
        return new StepCondition(null, false, false, false);
    }

    public StepCondition validate() {
        if (criteria != null) {
            criteria.validate();
        }
        return this;
    }
}
