package br.com.gestorcobranca.campaign.domain.repository;

import br.com.gestorcobranca.campaign.domain.model.Segment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface SegmentRepository {

    Segment save(Segment segment);

    Optional<Segment> findById(UUID id);

    boolean existsByCode(String code);

    /**
     * @param creditorIds credores permitidos (nulo = sem restrição)
     * @param includeTenantWide incluir segmentos da assessoria (sem credor)
     */
    Page<Segment> search(Collection<UUID> creditorIds, boolean includeTenantWide, UUID creditorId, Boolean active,
                         Pageable pageable);
}
