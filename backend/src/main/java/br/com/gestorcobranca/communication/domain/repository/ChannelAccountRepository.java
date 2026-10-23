package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ChannelType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelAccountRepository {

    ChannelAccount save(ChannelAccount account);

    ChannelAccount saveAndFlush(ChannelAccount account);

    Optional<ChannelAccount> findById(UUID id);

    /** Conta ativa do credor no canal (sem a padrão da assessoria). */
    Optional<ChannelAccount> findActiveForCreditor(ChannelType channel, UUID creditorId);

    /** Conta padrão ativa da assessoria no canal. */
    Optional<ChannelAccount> findActiveDefault(ChannelType channel);

    List<ChannelAccount> findAllByOrderByChannelAscNameAsc();
}
