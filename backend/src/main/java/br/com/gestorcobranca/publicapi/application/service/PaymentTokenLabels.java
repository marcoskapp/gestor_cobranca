package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.payment.application.port.spi.IntegrationTokenLabels;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementa a porta {@code payment :: spi} {@link IntegrationTokenLabels} (ADR-0010, H07): nome e prefixo de
 * exibição dos tokens que registraram pagamentos. A RLS limita à assessoria corrente; o escopo por credor é de quem
 * chama (o payment só pede os tokens dos pagamentos visíveis).
 */
@Component
class PaymentTokenLabels implements IntegrationTokenLabels {

    private final IntegrationTokenRepository tokens;

    PaymentTokenLabels(IntegrationTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TokenLabel> labels(Collection<UUID> tokenIds) {
        if (tokenIds == null || tokenIds.isEmpty()) {
            return Map.of();
        }
        return tokens.findAllByIdIn(tokenIds).stream()
                .map(t -> new TokenLabel(t.getId(), t.getName(), t.getDisplayPrefix(), t.getRevokedAt() != null))
                .collect(Collectors.toMap(TokenLabel::id, Function.identity()));
    }
}
