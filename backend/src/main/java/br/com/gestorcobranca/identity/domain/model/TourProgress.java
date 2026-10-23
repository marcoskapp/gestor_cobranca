package br.com.gestorcobranca.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;

/** Registro de um tour: versão percorrida, desfecho e quando ocorreu. */
@Embeddable
public class TourProgress {

    @Column(name = "tour_version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 10)
    private TourOutcome outcome;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected TourProgress() {
    }

    TourProgress(int version, TourOutcome outcome, Instant recordedAt) {
        this.version = version;
        this.outcome = outcome;
        this.recordedAt = recordedAt;
    }

    public int getVersion() {
        return version;
    }

    public TourOutcome getOutcome() {
        return outcome;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
