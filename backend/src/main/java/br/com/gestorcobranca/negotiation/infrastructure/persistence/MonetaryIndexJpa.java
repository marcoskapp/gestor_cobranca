package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MonetaryIndexJpa extends JpaRepository<MonetaryIndex, UUID> {

    Optional<MonetaryIndex> findByCode(String code);

    boolean existsByCode(String code);

    List<MonetaryIndex> findAllByOrderByCode();
}
