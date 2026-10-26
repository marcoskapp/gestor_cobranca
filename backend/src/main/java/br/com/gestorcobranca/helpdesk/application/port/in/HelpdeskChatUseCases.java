package br.com.gestorcobranca.helpdesk.application.port.in;

import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases.TicketView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat de suporte do usuário da sessão (sempre o próprio: não há consulta à conversa de outra pessoa). Liberado a
 * todos os perfis de usuário interno; o que a IA consulta passa pelas permissões e pelo escopo de quem pergunta.
 */
public interface HelpdeskChatUseCases {

    /** Disponibilidade do chat e da abertura de chamados, e o consumo do dia. */
    StatusView status();

    /**
     * Pergunta numa conversa (nova quando {@code conversationId} é nulo).
     *
     * @throws br.com.gestorcobranca.shared.domain.BusinessRuleException {@code helpdesk.unavailable} sem provedor,
     *                                                                    {@code helpdesk.limit_*} acima dos limites
     */
    AnswerView ask(AskCommand command);

    /** Conversa com o histórico (conteúdo descartado pela retenção aparece como removido). */
    ConversationView conversation(UUID conversationId);

    /** Conversas recentes do usuário (sem conteúdo). */
    List<ConversationSummary> recent(int limit);

    /** Avaliação 👍/👎 de uma resposta (pode ser trocada). */
    MessageView feedback(UUID messageId, FeedbackCommand command);

    /**
     * @param screen chave da tela atual no manual (ex.: {@code collection.queue}); chave desconhecida é ignorada
     */
    record AskCommand(UUID conversationId, String screen, String text) {
    }

    /** @param rating {@code UP} ou {@code DOWN}; {@code reason} só com {@code DOWN} (catálogo) */
    record FeedbackCommand(String rating, String reason) {
    }

    /**
     * @param available           há provedor de IA ligado
     * @param ticketsAvailable    abertura de chamados configurada
     * @param ticketsUnavailableReason {@code NOT_CONFIGURED} ou nulo
     */
    record StatusView(boolean available, boolean ticketsAvailable, String ticketsUnavailableReason,
                      int questionsToday, int questionsPerDay, String knowledgeVersion) {
    }

    record AnswerView(UUID conversationId, MessageView question, MessageView answer) {
    }

    /**
     * @param role    {@code USER} ou {@code ASSISTANT}
     * @param outcome resposta: {@code ANSWERED}, {@code UNANSWERED}, {@code OUT_OF_SCOPE}, {@code FAILED}
     * @param purged  conteúdo descartado pela retenção
     */
    record MessageView(UUID id, String role, String text, List<CardView> cards, List<ActionView> actions,
                       TicketView ticket, String outcome, String feedback, boolean purged, Instant createdAt) {
    }

    /**
     * Cartão de dado montado pelo servidor (o modelo não recebe nome nem documento: só a referência).
     *
     * @param ref    referência opaca da conversa ({@code D1}, {@code A2}…), a mesma que a IA usa no texto
     * @param kind   DEBTOR, CASE, AGREEMENT, PAYMENT, IMPORT, SETTLEMENT_STATEMENT, COMMISSION_STATEMENT, PENDING
     * @param route  rota da tela de detalhe (o roteador do frontend aplica as permissões)
     */
    record CardView(String ref, String kind, String title, String subtitle, String status, List<FieldView> fields,
                    String route, Map<String, String> queryParams) {
    }

    record FieldView(String label, String value) {
    }

    /**
     * @param kind   OPEN_SCREEN, START_TOUR ou ABOUT_SCREEN (os dois últimos só para a tela atual)
     * @param route  rota de OPEN_SCREEN
     */
    record ActionView(String kind, String screen, String label, String route) {
    }

    record ConversationView(UUID id, Instant startedAt, Instant lastActivityAt, int turns, List<MessageView> messages) {
    }

    record ConversationSummary(UUID id, Instant startedAt, Instant lastActivityAt, int turns, String screen) {
    }
}
