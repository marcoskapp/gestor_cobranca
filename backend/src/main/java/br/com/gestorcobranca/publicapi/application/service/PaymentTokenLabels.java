package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.payment.application.port.spi.IntegrationTokenLabels;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementa a porta {@code payment :: spi} {@link IntegrationTokenLabels} (ADR-0010, H07): nome e prefixo de
 * exibição dos tokens que registraram pagamentos e a situação efetiva agora ({@code IntegrationToken#stateAt}). A RLS
 * limita à assessoria corrente; o escopo por credor é de quem chama (o payment só pede os tokens dos pagamentos
 * visíveis).
 */
@Component
class PaymentTokenLabels implements IntegrationTokenLabels {

    private final IntegrationTokenRepository tokens;
    private final Clock clock;

    PaymentTokenLabels(IntegrationTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TokenLabel> labels(Collection<UUID> tokenIds) {
        if (tokenIds == null || tokenIds.isEmpty()) {
            return Map.of();
        }
        Instant now = Instant.now(clock);
        return tokens.findAllByIdIn(tokenIds).stream()
                .map(t -> new TokenLabel(t.getId(), t.getName(), t.getDisplayPrefix(), t.getRevokedAt() != null,
                        IntegrationTokenLabels.State.valueOf(t.stateAt(now).name())))
                .collect(Collectors.toMap(TokenLabel::id, Function.identity()));
    }
}
