package br.com.gestorcobranca.shared.web;

import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Após a autenticação e o roteamento, adiciona tenant, ator, sessão e rota ao MDC, de modo que
 * todo log técnico emitido durante o processamento seja atribuível.
 */
@Component
public class ActorContextInterceptor implements HandlerInterceptor {

    private final CurrentActor currentActor;

    public ActorContextInterceptor(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            MDC.put(MdcKeys.ROUTE, pattern.toString());
        }
        currentActor.find().ifPresent(actor -> {
            MDC.put(MdcKeys.TENANT_ID, actor.tenantId().toString());
            MDC.put(MdcKeys.ACTOR_ID, actor.id().toString());
            if (actor.sessionId() != null) {
                MDC.put(MdcKeys.SESSION_ID, actor.sessionId().toString());
            }
        });
        return true;
    }
}
