package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRestrictionRepository {

    ContactRestriction save(ContactRestriction restriction);

    Optional<ContactRestriction> findById(UUID id);

    /**
     * Restrições ativas (não revogadas) que podem alcançar um contato: as do devedor e as do
     * valor de contato informado. A vigência é avaliada pelo domínio.
     *
     * @param contactValueHash blind index do contato; nulo ignora restrições por contato sem devedor
     */
    List<ContactRestriction> findActiveForGate(UUID debtorId, String contactValueHash);

    /** Restrições ativas de um valor de contato (deduplicação de opt-out recebido repetidamente). */
    List<ContactRestriction> findActiveByContactValueHash(String contactValueHash);

    /** Todas as restrições do devedor, inclusive revogadas (dossiê de evidências). */
    List<ContactRestriction> findByDebtorIdOrderByCreatedAtAsc(UUID debtorId);

    /** Restrições temporárias que terminaram e ainda não foram comunicadas. */
    List<ContactRestriction> findExpiredUnnotified(Instant now, int limit);

    Page<ContactRestriction> search(RestrictionSearchCriteria criteria, Pageable pageable);
}
