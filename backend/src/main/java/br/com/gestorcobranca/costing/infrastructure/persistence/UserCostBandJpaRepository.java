package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.domain.model.UserCostBand;
import br.com.gestorcobranca.costing.domain.repository.UserCostBandRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface UserCostBandJpaRepository extends JpaRepository<UserCostBand, UUID>, UserCostBandRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from UserCostBand b where b.userId = :userId order by b.validFrom")
    List<UserCostBand> findAllByUserIdForUpdate(@Param("userId") UUID userId);
}
