package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Execução das etapas da carga: em nome de um usuário de demonstração (pelos casos de uso, com as permissões e o
 * escopo de credores do perfil dele), como sistema da assessoria, espera por processamentos assíncronos e cenários
 * opcionais (uma regra de negócio que recusa um cenário vira aviso na situação da carga, sem derrubar as demais).
 */
@SandboxSeedComponent
class SeedRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(SeedRuntime.class);
    private static final Duration POLL = Duration.ofMillis(500);

    private final TenantExecution tenantExecution;
    private final SeedStatus status;
    private final SandboxProperties properties;
    private final Clock clock;

    SeedRuntime(TenantExecution tenantExecution, SeedStatus status, SandboxProperties properties, Clock clock) {
        this.tenantExecution = tenantExecution;
        this.status = status;
        this.properties = properties;
        this.clock = clock;
    }

    /** Executa como o usuário de demonstração (nova transação). */
    <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    void asRun(Actor actor, Runnable action) {
        tenantExecution.callAs(actor, () -> {
            action.run();
            return null;
        });
    }

    /** Executa como o sistema da assessoria (nova transação). */
    <T> T system(java.util.UUID tenantId, Supplier<T> action) {
        return tenantExecution.callInTenant(tenantId, "sandbox.seed", action);
    }

    Instant now() {
        return Instant.now(clock);
    }

    Clock clock() {
        return clock;
    }

    /** Espera a condição (processamentos assíncronos: jobs, consumidores de eventos, envio de e-mail). */
    void await(String what, BooleanSupplier condition) {
        await(what, properties.seed().stepTimeout(), condition);
    }

    void await(String what, Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("tempo esgotado aguardando " + what);
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("carga interrompida aguardando " + what, e);
            }
        }
    }

    /**
     * Cenário de demonstração não essencial: recusa por regra de negócio (código estável do domínio) é registrada como
     * aviso e a carga continua; qualquer outra falha interrompe a carga.
     */
    void optional(String scenario, Runnable action) {
        optional(scenario, java.util.Set.of(), action);
    }

    /**
     * Como {@link #optional(String, Runnable)}, mas as recusas com os códigos informados são resultado esperado (ex.:
     * painéis atualizados há pouco pelo agendamento) e não viram aviso.
     */
    void optional(String scenario, java.util.Set<String> expectedCodes, Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            if (expectedCodes.contains(e.code())) {
                LOG.atDebug().addKeyValue("scenario", scenario).addKeyValue("code", e.code())
                        .log("Cenário do sandbox dispensado");
                return;
            }
            String warning = scenario + ": " + e.code();
            status.warn(warning);
            LOG.atWarn().addKeyValue("scenario", scenario).addKeyValue("code", e.code())
                    .log("Cenário do sandbox recusado por regra de negócio; seguindo sem ele");
        }
    }
}
