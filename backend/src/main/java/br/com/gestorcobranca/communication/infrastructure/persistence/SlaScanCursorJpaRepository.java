package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.SlaScanCursor;
import br.com.gestorcobranca.communication.domain.repository.SlaScanCursorRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SlaScanCursorJpaRepository extends JpaRepository<SlaScanCursor, UUID>, SlaScanCursorRepository {

    @Override
    Optional<SlaScanCursor> findByChannel(ChannelType channel);
}
