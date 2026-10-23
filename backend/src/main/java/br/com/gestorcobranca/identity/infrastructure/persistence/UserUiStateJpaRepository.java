package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.UserUiState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface UserUiStateJpaRepository extends JpaRepository<UserUiState, UUID> {

    Optional<UserUiState> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserUiState s where s.userId = :userId")
    Optional<UserUiState> findByUserIdForUpdate(UUID userId);

    /** O tenant vem da transação ({@code app.tenant_id}), o mesmo valor exigido pela política RLS. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into identity.user_ui_state
                (id, tenant_id, version, created_at, created_by, updated_at, updated_by, user_id, theme, density,
                 navigation_collapsed, hints_enabled, checklist_dismissed)
            values (:id, shared.current_tenant_id(), 0, :now, :userId, :now, :userId, :userId, 'SYSTEM', 'COMFORTABLE',
                    false, true, false)
            on conflict (tenant_id, user_id) do nothing
            """)
    int insertDefaultIfAbsent(UUID id, UUID userId, Instant now);
}
