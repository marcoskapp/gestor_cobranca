package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Vínculo de um usuário a um escritório parceiro. Um usuário pertence a no máximo um escritório
 * por vez (índice único parcial); o vínculo encerrado permanece para histórico.
 */
@Entity
@Table(name = "law_firm_member", schema = "legal")
public class LawFirmMember extends TenantScopedEntity {

    @Column(name = "law_firm_id", nullable = false, updatable = false)
    private UUID lawFirmId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    protected LawFirmMember() {
    }

    public LawFirmMember(UUID lawFirmId, UUID userId, Instant now) {
        Guard.require(lawFirmId != null && userId != null, "law_firm.member_required", "Escritório e usuário obrigatórios");
        this.lawFirmId = lawFirmId;
        this.userId = userId;
        this.active = true;
        this.joinedAt = now;
    }

    public void leave(Instant now) {
        Guard.requireState(active, "law_firm.member_inactive", "Vínculo já encerrado");
        this.active = false;
        this.leftAt = now;
    }

    public UUID getLawFirmId() {
        return lawFirmId;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }
}
