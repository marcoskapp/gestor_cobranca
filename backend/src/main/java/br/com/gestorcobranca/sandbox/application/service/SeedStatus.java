package br.com.gestorcobranca.sandbox.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Situação da carga do sandbox, publicada no grupo de prontidão ({@code /actuator/health/readiness}): o sandbox só
 * fica "pronto" quando a carga termina — o {@code up.sh} e o healthcheck do contêiner esperam por isso.
 */
@Component
@Profile("sandbox")
public class SeedStatus {

    public enum State { DISABLED, PENDING, RUNNING, COMPLETED, FAILED }

    private volatile State state;
    private volatile String phase = "aguardando a subida da aplicação";
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String failure;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    SeedStatus(@Value("${app.sandbox.seed.enabled:false}") boolean enabled) {
        this.state = enabled ? State.PENDING : State.DISABLED;
        if (!enabled) {
            this.phase = "carga desligada (app.sandbox.seed.enabled=false)";
        }
    }

    void started(Instant now) {
        state = State.RUNNING;
        startedAt = now;
    }

    void phase(String description) {
        phase = description;
    }

    void warn(String warning) {
        warnings.add(warning);
    }

    void completed(Instant now) {
        state = State.COMPLETED;
        phase = "concluída";
        finishedAt = now;
    }

    void failed(Instant now, String reason) {
        state = State.FAILED;
        failure = reason;
        finishedAt = now;
    }

    public State state() {
        return state;
    }

    /** Pronto para uso: carga concluída ou desligada. */
    public boolean ready() {
        return state == State.COMPLETED || state == State.DISABLED;
    }

    public String phase() {
        return phase;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String failure() {
        return failure;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
