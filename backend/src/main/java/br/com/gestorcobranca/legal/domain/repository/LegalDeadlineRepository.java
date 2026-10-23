package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalDeadlineRepository {

    LegalDeadline save(LegalDeadline deadline);

    Optional<LegalDeadline> findById(UUID id);

    List<LegalDeadline> findByMatterIdOrderByDueDateAscCreatedAtAsc(UUID matterId);

    List<LegalDeadline> findByMatterIdAndStatus(UUID matterId, DeadlineStatus status);

    /** Pendentes em lotes por cursor (varredura diária). */
    List<LegalDeadline> findByStatusAndIdGreaterThanOrderByIdAsc(DeadlineStatus status, UUID afterId, Pageable page);

    Page<LegalDeadline> search(DeadlineSearchCriteria criteria, Pageable pageable);
}
