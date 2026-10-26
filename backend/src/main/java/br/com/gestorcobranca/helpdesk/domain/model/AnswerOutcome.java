package br.com.gestorcobranca.helpdesk.domain.model;

/**
 * Resultado de uma pergunta (métrica de negócio "perguntas sem resposta").
 * <ul>
 *   <li>{@code ANSWERED}: a IA respondeu e indicou que resolveu;</li>
 *   <li>{@code UNANSWERED}: a IA não soube ou não conseguiu resolver (pode ter preparado um chamado);</li>
 *   <li>{@code OUT_OF_SCOPE}: pedido fora do sistema (recusado);</li>
 *   <li>{@code FAILED}: falha técnica (provedor indisponível, tempo esgotado, limite de rodadas).</li>
 * </ul>
 */
public enum AnswerOutcome { ANSWERED, UNANSWERED, OUT_OF_SCOPE, FAILED }
