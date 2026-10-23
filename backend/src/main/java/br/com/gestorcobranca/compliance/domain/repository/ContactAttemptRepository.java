package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.ContactAttempt;
import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContactAttemptRepository {

    ContactAttempt save(ContactAttempt attempt);

    /** Instantes das tentativas do devedor no canal (todos os credores) a partir de {@code since}. */
    List<Instant> findAttemptTimes(UUID debtorId, ContactChannel channel, Instant since);

    /** @param allowedCreditorIds {@code null} = sem restrição */
    Page<ContactAttempt> findByDebtor(UUID debtorId, Collection<UUID> allowedCreditorIds, Pageable pageable);

    List<ContactAttempt> findByDebtorIdOrderByAttemptedAtDesc(UUID debtorId);
}
