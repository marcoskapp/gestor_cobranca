package br.com.gestorcobranca.collection.application.port.in;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Link seguro das evidências enviadas ao credor no pedido da contestação (endpoint público, sem login): token
 * aleatório de 256 bits no link do e-mail, só o hash no banco, validade e limite de downloads. Entrega um ZIP com as
 * evidências escolhidas pelo analista. Acesso registrado no log de acesso ({@code FILE_DOWNLOADED} ou
 * {@code DISPUTE_LINK_REJECTED}); token inválido, vencido ou esgotado responde 404, sem revelar qual.
 */
public interface DisputeEvidenceLinkUseCases {

    /** Valida o token e reserva um download. @throws br.com.gestorcobranca.shared.domain.ResourceNotFoundException */
    Package open(String token, String clientIp);

    /**
     * Registra a recusa por excesso de tentativas do mesmo IP (antes de validar o token): métrica e log de acesso,
     * para alarme de varredura de tokens.
     */
    void rateLimited(String clientIp);

    /** Escreve o ZIP do pacote (no contexto da assessoria). */
    void write(Package pack, OutputStream out) throws IOException;

    record Package(UUID tenantId, UUID requestId, String fileName) {
    }
}
