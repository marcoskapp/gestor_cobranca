package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndexRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface MonetaryIndexRateJpa extends JpaRepository<MonetaryIndexRate, UUID> {

    @Query("select r from MonetaryIndexRate r where r.indexId = :indexId order by r.competence")
    List<MonetaryIndexRate> findByIndex(@Param("indexId") UUID indexId);
}
