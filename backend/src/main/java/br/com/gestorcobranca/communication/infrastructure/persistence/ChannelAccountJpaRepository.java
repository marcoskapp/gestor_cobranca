package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ChannelAccountJpaRepository extends JpaRepository<ChannelAccount, UUID>, ChannelAccountRepository {

    @Override
    @Query("select a from ChannelAccount a where a.active and a.channel = :channel and a.creditorId = :creditorId")
    Optional<ChannelAccount> findActiveForCreditor(ChannelType channel, UUID creditorId);

    @Override
    @Query("select a from ChannelAccount a where a.active and a.channel = :channel and a.creditorId is null")
    Optional<ChannelAccount> findActiveDefault(ChannelType channel);

    @Override
    List<ChannelAccount> findAllByOrderByChannelAscNameAsc();
}
