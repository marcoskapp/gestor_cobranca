package br.com.gestorcobranca.campaign.domain.model;

/** Ação de uma etapa: mensagem por template (communication), tarefa/retorno no caso (collection), entregar o atendimento ao assistente, mover para a fila (equipe) ou gerar oferta/link de pagamento (porta {@code CampaignOfferPort}). Entregar ao assistente e mover para a fila encerram a inscrição. */
@org.springframework.modulith.NamedInterface("usecases")
public enum ActionType {
    SEND_MESSAGE, CREATE_TASK, HAND_TO_ASSISTANT, MOVE_TO_QUEUE, OFFER
}
