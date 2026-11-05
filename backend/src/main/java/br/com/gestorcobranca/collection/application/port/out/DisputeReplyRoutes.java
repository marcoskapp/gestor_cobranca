package br.com.gestorcobranca.collection.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Rotas dos endereços de resposta dos pedidos ao credor (tabela global {@code collection.dispute_reply_route}, sem
 * RLS: o e-mail chega sem assessoria) e registro dos e-mails ao subdomínio sem pedido correspondente
 * ({@code collection.inbound_mail_unrouted}). Só o hash SHA-256 do token, ids e a validade; nenhum dado do devedor.
 */
public interface DisputeReplyRoutes {

    void register(String tokenHash, UUID tenantId, UUID requestId, Instant expiresAt);

    Optional<Route> find(String tokenHash);

    /**
     * E-mail sem pedido correspondente (sem endereço de resposta ou token desconhecido). Idempotente pelo id do
     * provedor. @return true se registrou agora
     */
    boolean recordUnrouted(String providerMessageId, Instant receivedAt, String rawKey, long size, String reason);

    record Route(UUID tenantId, UUID requestId, Instant expiresAt) {
    }
}
