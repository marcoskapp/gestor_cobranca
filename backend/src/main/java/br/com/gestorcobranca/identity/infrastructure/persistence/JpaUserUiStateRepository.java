package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.UserUiState;
import br.com.gestorcobranca.identity.domain.repository.UserUiStateRepository;
import br.com.gestorcobranca.shared.domain.Ids;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaUserUiStateRepository implements UserUiStateRepository {

    private final UserUiStateJpaRepository states;

    JpaUserUiStateRepository(UserUiStateJpaRepository states) {
        this.states = states;
    }

    @Override
    public Optional<UserUiState> findByUserId(UUID userId) {
        return states.findByUserId(userId);
    }

    @Override
    public UserUiState lockOrCreate(UUID userId, Instant now) {
        states.insertDefaultIfAbsent(Ids.newId(), userId, now);
        return states.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Estado de interface não encontrado após a criação"));
    }
}
