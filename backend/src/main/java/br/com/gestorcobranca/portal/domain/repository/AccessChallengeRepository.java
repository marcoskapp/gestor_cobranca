package br.com.gestorcobranca.portal.domain.repository;

import br.com.gestorcobranca.portal.domain.model.AccessChallenge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessChallengeRepository {

    AccessChallenge save(AccessChallenge challenge);

    /** Com lock: tentativas concorrentes do mesmo desafio contam todas. */
    Optional<AccessChallenge> findByIdForUpdate(UUID id);

    List<AccessChallenge> findByDebtorId(UUID debtorId);

    /** Códigos (OTP) pedidos para o documento ({@code documentHash}) desde o instante — teto diário (decisão 1083). */
    long countCodesSince(String documentHash, Instant since);

    /**
     * Retenção (LGPD): apaga até {@code limit} desafios vencidos antes do corte, inclusive os fictícios (documento
     * de quem não é devedor).
     *
     * @return desafios apagados
     */
    int deleteExpiredBefore(Instant cutoff, int limit);
}
