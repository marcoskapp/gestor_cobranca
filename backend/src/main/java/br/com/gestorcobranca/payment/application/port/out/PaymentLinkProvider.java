package br.com.gestorcobranca.payment.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Porta de link de pagamento (cartão/carteiras). Sem provedor definido (issue #12): nenhum adaptador
 * em produção, e instruções {@code LINK} são recusadas até existir um.
 */
public interface PaymentLinkProvider {

    LinkResult create(LinkRequest request);

    void cancel(String providerId);

    record LinkRequest(UUID instructionId, BigDecimal amount, Instant expiresAt, String description) {
    }

    record LinkResult(String providerId, String url) {
    }
}
