package br.com.gestorcobranca.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;

/** Última versão de uma tela já vista pelo usuário. */
@Embeddable
public class ScreenVisit {

    @Column(name = "seen_version", nullable = false)
    private int version;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    protected ScreenVisit() {
    }

    ScreenVisit(int version, Instant seenAt) {
        this.version = version;
        this.seenAt = seenAt;
    }

    public int getVersion() {
        return version;
    }

    public Instant getSeenAt() {
        return seenAt;
    }
}
