package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.domain.model.ChannelTariff;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.costing.domain.repository.ChannelTariffRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface ChannelTariffJpaRepository extends JpaRepository<ChannelTariff, UUID>, ChannelTariffRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ChannelTariff t where t.channel = :channel order by t.validFrom")
    List<ChannelTariff> findAllByChannelForUpdate(@Param("channel") TariffChannel channel);
}
