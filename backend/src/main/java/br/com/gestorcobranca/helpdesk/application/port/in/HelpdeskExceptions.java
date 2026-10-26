package br.com.gestorcobranca.helpdesk.application.port.in;

import br.com.gestorcobranca.shared.domain.DomainException;

import java.util.Map;

/** Recusas do chat de suporte com resposta HTTP própria (429 e 503). */
public final class HelpdeskExceptions {

    private HelpdeskExceptions() {
    }

    /**
     * Limite de uso atingido (HTTP 429): {@code helpdesk.limit_rate} (perguntas por minuto),
     * {@code helpdesk.limit_daily} (perguntas ou custo do dia), {@code helpdesk.limit_conversation} (perguntas ou custo
     * da conversa — comece outra).
     */
    public static class LimitReached extends DomainException {

        public LimitReached(String code, String message, Map<String, Object> details) {
            super(code, message, details);
        }
    }

    /** Chat sem provedor de IA ou abertura de chamado sem configuração (HTTP 503). */
    public static class Unavailable extends DomainException {

        public Unavailable(String code, String message) {
            super(code, message);
        }
    }
}
