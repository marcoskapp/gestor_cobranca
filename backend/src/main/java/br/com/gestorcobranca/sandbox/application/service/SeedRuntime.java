package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.port.out.DemoTimeMachine;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Execução das etapas da carga: em nome de um usuário de demonstração (pelos casos de uso, com as permissões e o
 * escopo de credores do perfil dele), como sistema da assessoria, espera por processamentos assíncronos e cenários
 * opcionais (uma regra de negócio que recusa um cenário vira aviso na situação da carga, sem derrubar as demais) e
 * viagens no relógio de demonstração ({@link DemoTimeMachine}) para registrar o histórico fictício.
 */
@SandboxSeedComponent
class SeedRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(SeedRuntime.class);
    private static final Duration POLL = Duration.ofMillis(500);
    /** Espera curta entre conferências da fila assíncrona (antes de cada salto do relógio). */
    private static final Duration QUIET_POLL = Duration.ofMillis(100);
    private static final Duration QUIET_TIMEOUT = Duration.ofSeconds(60);

    private final TenantExecution tenantExecution;
    private final SeedStatus status;
    private final SandboxProperties properties;
    private final Clock clock;
    private final DemoTimeMachine timeMachine;
    private final MeterRegistry meters;

    SeedRuntime(TenantExecution tenantExecution, SeedStatus status, SandboxProperties properties, Clock clock,
                DemoTimeMachine timeMachine, MeterRegistry meters) {
        this.tenantExecution = tenantExecution;
        this.status = status;
        this.properties = properties;
        this.clock = clock;
        this.timeMachine = timeMachine;
        this.meters = meters;
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

    /** Agora no relógio da aplicação (deslocado durante o histórico). */
    Instant now() {
        return Instant.now(clock);
    }

    /** Hoje no fuso de operação, no relógio da aplicação. */
    LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    /** Hora real (situação da carga, e-mails do Mailpit, ativação de usuários). */
    Instant realNow() {
        return timeMachine.realNow();
    }

    Clock clock() {
        return clock;
    }

    // ------------------------------------------------------------------------------------- relógio de demonstração

    boolean shifted() {
        return timeMachine.shifted();
    }

    /**
     * Desloca o relógio para o instante (fuso de operação). Antes do salto espera os processamentos assíncronos já
     * disparados terminarem, para que consumidores e jobs gravem a data do período em que o fato aconteceu.
     */
    void travelTo(LocalDateTime when) {
        Instant target = when.atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        awaitQuiet();
        timeMachine.travelTo(target);
        status.clock(target);
    }

    /**
     * Avança o relógio deslocado até o instante (nunca volta: instante já passado = nada a fazer). Sem deslocamento em
     * curso, viaja até ele. Na virada do dia, antes espera os processamentos assíncronos (consumidores e jobs gravam a
     * data do dia em que o fato aconteceu); dentro do dia, um efeito assíncrono pode ficar minutos depois do fato.
     */
    void advance(Instant target) {
        if (timeMachine.shifted() && !target.isAfter(now())) {
            return;
        }
        // dentro do mesmo dia o salto é de minutos: só a virada do dia espera os processamentos assíncronos
        if (!timeMachine.shifted() || !today().equals(LocalDate.ofInstant(target, TimeConfig.BUSINESS_ZONE))) {
            awaitQuiet();
        }
        timeMachine.travelTo(target);
        status.clock(target);
    }

    void advance(LocalDateTime when) {
        advance(when.atZone(TimeConfig.BUSINESS_ZONE).toInstant());
    }

    /** Volta ao tempo real (depois de esperar os processamentos assíncronos do período). */
    void backToPresent() {
        if (!timeMachine.shifted()) {
            return;
        }
        awaitQuiet();
        timeMachine.backToPresent();
        status.clock(null);
    }

    /** Volta ao tempo real sem esperar nada (falha da carga). */
    void abortTravel() {
        timeMachine.backToPresent();
        status.clock(null);
    }

    /** Executa a ação com o relógio no instante informado e volta ao presente (inclusive em falha). */
    void inThePast(LocalDateTime when, Runnable action) {
        travelTo(when);
        try {
            action.run();
        } finally {
            backToPresent();
        }
    }

    /**
     * Espera a aplicação ficar quieta: consumidores de eventos sem tarefa em execução ou na fila, nenhum job pronto
     * aguardando e nenhuma publicação de evento pendente (métricas técnicas {@code executor.*{name=events}},
     * {@code jobs.pending} e {@code events.publications.incomplete}). Publicações pendentes que não mudam por
     * {@link #STUCK_AFTER} com os consumidores parados são de consumidor com falha (reentregues pelo agendamento) e não
     * seguram a carga. Sem silêncio no prazo, registra aviso e segue.
     */
    void awaitQuiet() {
        long[] incomplete = {-1, System.nanoTime()};
        try {
            await("processamentos assíncronos", QUIET_TIMEOUT, QUIET_POLL, () -> quiet(incomplete));
        } catch (IllegalStateException e) {
            warn("processamentos assíncronos ainda pendentes antes do salto do relógio de demonstração");
        }
    }

    private static final Duration STUCK_AFTER = Duration.ofSeconds(3);

    private boolean quiet(long[] incomplete) {
        if (gauge("executor.active", "events") > 0 || gauge("executor.queued", "events") > 0
                || gauge("jobs.pending", null) > 0) {
            incomplete[0] = -1;
            return false;
        }
        long pending = (long) gauge("events.publications.incomplete", null);
        if (pending == 0) {
            return true;
        }
        if (pending != incomplete[0]) {
            incomplete[0] = pending;
            incomplete[1] = System.nanoTime();
            return false;
        }
        return System.nanoTime() - incomplete[1] >= STUCK_AFTER.toNanos();
    }

    private double gauge(String name, String executor) {
        var search = meters.find(name);
        if (executor != null) {
            search = search.tag("name", executor);
        }
        Gauge g = search.gauge();
        return g == null ? 0 : g.value();
    }

    /** Espera a condição (processamentos assíncronos: jobs, consumidores de eventos, envio de e-mail). */
    void await(String what, BooleanSupplier condition) {
        await(what, properties.seed().stepTimeout(), condition);
    }

    void await(String what, Duration timeout, BooleanSupplier condition) {
        await(what, timeout, POLL, condition);
    }

    void await(String what, Duration timeout, Duration poll, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("tempo esgotado aguardando " + what);
            }
            try {
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("carga interrompida aguardando " + what, e);
            }
        }
    }

    /** Etapa em andamento na situação da carga (prontidão). */
    void phase(String description) {
        status.phase(description);
    }

    /** Aviso da carga (detalhes da prontidão e log técnico) sem interrompê-la. */
    void warn(String warning) {
        status.warn(warning);
        LOG.atWarn().addKeyValue("warning", warning).log("Aviso da carga do sandbox");
    }

    /** Falhas inesperadas toleradas no histórico antes de interromper a carga (banco fora, defeito sistemático). */
    private static final int HISTORY_FAILURE_LIMIT = 40;
    private final java.util.Map<String, Integer> historyRefusals = new java.util.concurrent.ConcurrentHashMap<>();
    private int historyFailures;

    /**
     * Fato do histórico fictício: recusa por regra de negócio é contada por cenário e código (resumida na situação da
     * carga ao fim do histórico, {@link #flushHistory()}); falha inesperada vira aviso e log técnico com a causa, e a
     * carga só para depois de {@link #HISTORY_FAILURE_LIMIT} delas — um fato fictício isolado não derruba o sandbox.
     */
    void event(String scenario, Runnable action) {
        event(scenario, java.util.Set.of(), action);
    }

    void event(String scenario, java.util.Set<String> expectedCodes, Runnable action) {
        // sem o "(contrato)" do cenário em aviso e log: o número do contrato identifica o devedor (mesmo fictício)
        String kind = scenario.replaceAll(" \\(.*\\)$", "");
        try {
            action.run();
        } catch (DomainException e) {
            if (!expectedCodes.contains(e.code())) {
                historyRefusals.merge(kind + ": " + e.code(), 1, Integer::sum);
                LOG.atDebug().addKeyValue("scenario", kind).addKeyValue("code", e.code())
                        .log("Fato do histórico recusado por regra de negócio");
            }
        } catch (RuntimeException e) {
            historyFailures++;
            warn(kind + ": " + e.getClass().getSimpleName());
            LOG.atWarn().addKeyValue("scenario", kind).setCause(e).log("Falha num fato do histórico do sandbox");
            if (historyFailures > HISTORY_FAILURE_LIMIT) {
                throw new IllegalStateException("histórico interrompido: falhas demais (" + historyFailures + ")", e);
            }
        }
    }

    /** Resume as recusas do histórico na situação da carga (uma linha por cenário e código). */
    void flushHistory() {
        historyRefusals.forEach((key, count) -> warn("histórico: " + count + "× " + key));
        historyRefusals.clear();
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
