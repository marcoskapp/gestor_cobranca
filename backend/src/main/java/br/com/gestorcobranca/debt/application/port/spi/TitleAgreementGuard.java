package br.com.gestorcobranca.debt.application.port.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Acordo em vigor que cobre o título, se houver (implementada pelo negotiation). O valor de um título num acordo em
 * vigor não é corrigido manualmente: o acordo fixou o valor negociado com o devedor; a correção exige renegociar ou
 * cancelar o acordo antes.
 */
public interface TitleAgreementGuard {

    Optional<UUID> activeAgreementCovering(UUID titleId);
}
