package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.domain.model.CostBand;
import br.com.gestorcobranca.costing.domain.repository.CostBandRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface CostBandJpaRepository extends JpaRepository<CostBand, UUID>, CostBandRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CostBand b where b.name = :name order by b.validFrom")
    List<CostBand> findAllByNameForUpdate(@Param("name") String name);
}
