package br.com.gestorcobranca.assistant.domain.model;

/**
 * Modo de operação do assistente (docs/negocio/assistente-ia.md §6).
 * <ul>
 *   <li>{@code OFF}: não atua (conversas que ele conduzia vão para humanos na próxima mensagem).</li>
 *   <li>{@code SHADOW}: rascunho para o operador; nada sai sem humano.</li>
 *   <li>{@code COPILOT}: rascunho com a próxima oferta sugerida para o operador.</li>
 *   <li>{@code AUTONOMOUS_WITH_APPROVAL}: conversa sozinho; o acordo aceito é formalizado por humano.</li>
 *   <li>{@code AUTONOMOUS}: conversa e formaliza dentro da alçada (o perfil precisa de {@code canFormalize}).</li>
 * </ul>
 */
public enum AssistantMode {
    OFF,
    SHADOW,
    COPILOT,
    AUTONOMOUS_WITH_APPROVAL,
    AUTONOMOUS;

    /** Responde diretamente ao devedor nas conversas que conduz. */
    public boolean autonomous() {
        return this == AUTONOMOUS || this == AUTONOMOUS_WITH_APPROVAL;
    }

    /** Gera rascunhos para o operador nas conversas com humanos. */
    public boolean drafts() {
        return this == SHADOW || this == COPILOT;
    }
}
