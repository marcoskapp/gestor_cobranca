package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases;
import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases.InboxSignal;
import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases.Subscription;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CredentialRevalidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conexões SSE da caixa de entrada abertas nesta instância. Cada conexão:
 * <ul>
 *   <li>recebe só avisos da própria assessoria e dos credores do escopo (filtrados no hub);</li>
 *   <li>tem duração máxima (padrão 5 min, nunca além da validade do token): o cliente reconecta
 *       com o token vigente — também é assim que conexões se redistribuem entre instâncias;</li>
 *   <li>recebe um comentário {@code ping} periódico (abaixo do idle timeout do ALB) e tem token e
 *       sessão revalidados a cada minuto: sessão revogada ou expirada encerra a conexão;</li>
 *   <li>é encerrada no desligamento da instância (o cliente reconecta noutra).</li>
 * </ul>
 * Limites por usuário e por instância evitam esgotar conexões; acima deles, 503 e o cliente segue
 * só com a atualização periódica.
 */
@Component
class InboxStreams implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(InboxStreams.class);
    private static final long CLIENT_RETRY_MS = 5_000;

    private final InboxRealtimeUseCases realtime;
    private final CredentialRevalidator revalidator;
    private final CommunicationProperties.Realtime properties;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    private volatile ScheduledExecutorService heartbeat;
    private volatile ExecutorService delivery;
    private volatile boolean running;

    InboxStreams(InboxRealtimeUseCases realtime, CredentialRevalidator revalidator, CommunicationProperties properties,
                 MeterRegistry registry, Clock clock) {
        this.realtime = realtime;
        this.revalidator = revalidator;
        this.properties = properties.realtime();
        this.registry = registry;
        this.clock = clock;
    }

    private final class Stream {

        final SseEmitter emitter;
        final UUID userId;
        final ActorAuthentication authentication;
        final Instant expiresAt;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile Subscription subscription;
        volatile Instant revalidatedAt;

        Stream(SseEmitter emitter, ActorAuthentication authentication, Instant expiresAt, Instant now) {
            this.emitter = emitter;
            this.userId = authentication.actor().id();
            this.authentication = authentication;
            this.expiresAt = expiresAt;
            this.revalidatedAt = now;
        }

        void send(SseEmitter.SseEventBuilder event) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                close("client_gone", false);
            }
        }

        void close(String outcome, boolean complete) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            streams.remove(this);
            Subscription s = subscription;
            if (s != null) {
                s.close();
            }
            if (complete) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // resposta já encerrada
                }
            }
            registry.counter("communication.realtime.streams", "outcome", outcome).increment();
        }
    }

    SseEmitter open(ActorAuthentication authentication) {
        if (!properties.enabled() || !running) {
            throw unavailable("Atualização em tempo real indisponível");
        }
        UUID userId = authentication.actor().id();
        if (streams.size() >= properties.maxStreams()
                || streams.stream().filter(s -> s.userId.equals(userId)).count() >= properties.maxStreamsPerUser()) {
            registry.counter("communication.realtime.streams", "outcome", "rejected_limit").increment();
            throw unavailable("Limite de conexões em tempo real atingido");
        }
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.maxStreamDuration());
        Optional<Instant> tokenExpiry = revalidator.validUntil(authentication);
        if (tokenExpiry.isPresent() && tokenExpiry.get().isBefore(expiresAt)) {
            expiresAt = tokenExpiry.get();
        }
        // o timeout do servlet fica além da duração máxima: quem encerra é a varredura, com evento claro
        SseEmitter emitter = new SseEmitter(Duration.between(now, expiresAt).plusSeconds(30).toMillis());
        Stream stream = new Stream(emitter, authentication, expiresAt, now);
        emitter.onCompletion(() -> stream.close("completed", false));
        emitter.onTimeout(() -> stream.close("timeout", true));
        emitter.onError(e -> stream.close("client_gone", false));
        // a assinatura captura assessoria e escopo do ator desta requisição
        stream.subscription = realtime.subscribe(signal -> delivery.execute(() -> stream.send(event(signal))));
        streams.add(stream);
        registry.counter("communication.realtime.streams", "outcome", "opened").increment();
        stream.send(SseEmitter.event().reconnectTime(CLIENT_RETRY_MS).name("ready")
                .data(Map.of("expiresAt", expiresAt.toString())));
        return emitter;
    }

    private static SseEmitter.SseEventBuilder event(InboxSignal signal) {
        return InboxSignal.RESYNC.equals(signal.kind()) ? SseEmitter.event().name("resync").data(Map.of())
                : SseEmitter.event().name("conversation").data(Map.of("conversationId", signal.conversationId().toString()));
    }

    /** Ping, expiração e revalidação periódica da sessão de cada conexão. */
    void sweep() {
        Instant now = Instant.now(clock);
        for (Stream s : streams) {
            if (!now.isBefore(s.expiresAt)) {
                s.send(SseEmitter.event().name("expired").data(Map.of()));
                s.close("expired", true);
                continue;
            }
            if (!now.isBefore(s.revalidatedAt.plus(properties.revalidation()))) {
                if (revalidator.validUntil(s.authentication).isEmpty()) {
                    s.send(SseEmitter.event().name("unauthorized").data(Map.of()));
                    s.close("revoked", true);
                    continue;
                }
                s.revalidatedAt = now;
            }
            s.send(SseEmitter.event().comment("ping"));
        }
    }

    int openStreams() {
        return streams.size();
    }

    @Override
    public void start() {
        delivery = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("inbox-sse-delivery").daemon().factory());
        heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("inbox-sse-heartbeat").daemon()
                .factory());
        long period = properties.heartbeat().toMillis();
        heartbeat.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (RuntimeException e) {
                LOG.atWarn().setCause(e).log("Falha na manutenção das conexões em tempo real");
            }
        }, period, period, TimeUnit.MILLISECONDS);
        running = true;
    }

    /** Desligamento: encerra as conexões antes do servidor (o desligamento gracioso não espera por elas). */
    @Override
    public void stop() {
        running = false;
        for (Stream s : streams) {
            s.send(SseEmitter.event().name("reconnect").data(Map.of()));
            s.close("shutdown", true);
        }
        heartbeat.shutdownNow();
        delivery.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Para antes do servidor web (fase do desligamento gracioso do Tomcat). */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
