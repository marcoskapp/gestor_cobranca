package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ResponseTarget;
import br.com.gestorcobranca.communication.domain.repository.ResponseTargetRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ResponseTargetJpaRepository extends JpaRepository<ResponseTarget, UUID>, ResponseTargetRepository {

    @Override
    Optional<ResponseTarget> findByChannel(ChannelType channel);
}
