package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.SlaScanCursor;

import java.util.Optional;

public interface SlaScanCursorRepository {

    SlaScanCursor save(SlaScanCursor cursor);

    Optional<SlaScanCursor> findByChannel(ChannelType channel);
}
