package br.com.gestorcobranca.identity.domain.repository;

import br.com.gestorcobranca.identity.domain.model.UserUiState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserUiStateRepository {

    Optional<UserUiState> findByUserId(UUID userId);

    /**
     * Garante que exista o estado do usuário (com valores padrão) e o devolve bloqueado para
     * alteração. Requisições simultâneas do mesmo usuário (várias abas, vários dispositivos) são
     * serializadas, sem conflito na criação.
     */
    UserUiState lockOrCreate(UUID userId, Instant now);
}
