package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.PasswordHistoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface PasswordHistoryJpaRepository extends JpaRepository<PasswordHistoryEntry, UUID> {

    List<PasswordHistoryEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
