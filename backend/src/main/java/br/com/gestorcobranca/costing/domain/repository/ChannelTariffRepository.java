package br.com.gestorcobranca.costing.domain.repository;

import br.com.gestorcobranca.costing.domain.model.ChannelTariff;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelTariffRepository {

    ChannelTariff save(ChannelTariff tariff);

    /** Grava já: o encerramento da vigência anterior precisa chegar ao banco antes da nova (exclusão de sobreposição). */
    ChannelTariff saveAndFlush(ChannelTariff tariff);

    Optional<ChannelTariff> findById(UUID id);

    /** Vigências do canal, bloqueadas para alteração. */
    List<ChannelTariff> findAllByChannelForUpdate(TariffChannel channel);

    List<ChannelTariff> findAllByOrderByChannelAscValidFromAsc();
}
