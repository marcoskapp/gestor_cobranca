package br.com.gestorcobranca.payment.application.port.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Nome dos tokens de integração que registraram pagamentos (canal {@code INTEGRATION}, H07). Implementada pelo
 * publicapi; o payment só a chama com ids que já estão nos pagamentos visíveis para quem consulta (o escopo por credor
 * é do payment). Nunca devolve segredo: só o nome, o prefixo de exibição e se o token foi revogado. O nome é o atual
 * (editável no cadastro do token).
 */
public interface IntegrationTokenLabels {

    /** Rótulos dos tokens pedidos; ids que não são de token (ou de outra assessoria) ficam fora do mapa. */
    Map<UUID, TokenLabel> labels(Collection<UUID> tokenIds);

    record TokenLabel(UUID id, String name, String displayPrefix, boolean revoked) {
    }
}
