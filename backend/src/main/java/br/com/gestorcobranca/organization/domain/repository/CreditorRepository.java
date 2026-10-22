package br.com.gestorcobranca.organization.domain.repository;

import br.com.gestorcobranca.organization.domain.model.Creditor;
import br.com.gestorcobranca.organization.domain.model.CreditorStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditorRepository {

    Creditor save(Creditor creditor);

    Optional<Creditor> findById(UUID id);

    boolean existsByDocument(String document);

    List<Creditor> findAllByIdIn(Collection<UUID> ids);

    /**
     * @param allowedIds credores visíveis ao ator; {@code null} = sem restrição
     * @param text       busca por nome/código (opcional)
     */
    Page<Creditor> search(Collection<UUID> allowedIds, String text, CreditorStatus status, Pageable pageable);
}
