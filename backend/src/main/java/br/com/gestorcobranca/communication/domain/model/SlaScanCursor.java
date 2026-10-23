package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Até onde a varredura de SLA já examinou os estouros de um canal na assessoria: cada conversa
 * que cruza o limite sem resposta é contada uma única vez, mesmo com execuções atrasadas ou
 * puladas (o próximo ciclo retoma do cursor).
 */
@Entity
@Table(name = "sla_scan_cursor", schema = "communication")
public class SlaScanCursor extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 20)
    private ChannelType channel;

    @Column(name = "scanned_until", nullable = false)
    private Instant scannedUntil;

    protected SlaScanCursor() {
    }

    public SlaScanCursor(ChannelType channel, Instant scannedUntil) {
        this.channel = channel;
        this.scannedUntil = scannedUntil;
    }

    /** Avança o cursor (nunca retrocede). */
    public void advanceTo(Instant until) {
        Guard.require(until != null, "sla_scan.until_required", "Instante obrigatório");
        if (until.isAfter(scannedUntil)) {
            this.scannedUntil = until;
        }
    }

    public ChannelType getChannel() {
        return channel;
    }

    public Instant getScannedUntil() {
        return scannedUntil;
    }
}
