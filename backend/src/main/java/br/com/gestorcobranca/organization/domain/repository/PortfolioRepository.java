package br.com.gestorcobranca.organization.domain.repository;

import br.com.gestorcobranca.organization.domain.model.Portfolio;
import br.com.gestorcobranca.organization.domain.model.PortfolioStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioRepository {

    Portfolio save(Portfolio portfolio);

    Optional<Portfolio> findById(UUID id);

    List<Portfolio> findAllByIdIn(Collection<UUID> ids);

    boolean existsByCreditorIdAndCode(UUID creditorId, String code);

    /**
     * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição
     */
    Page<Portfolio> search(Collection<UUID> allowedCreditorIds, UUID creditorId, PortfolioStatus status, String text,
                           Pageable pageable);
}
