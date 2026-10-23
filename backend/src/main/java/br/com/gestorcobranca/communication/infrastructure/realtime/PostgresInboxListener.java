package br.com.gestorcobranca.communication.infrastructure.realtime;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.out.InboxChangeSink;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/**
 * Escuta o canal {@code communication_inbox} do PostgreSQL (LISTEN/NOTIFY) numa conexão dedicada,
 * fora do pool, e repassa os avisos ao {@link InboxChangeSink} desta instância. Os avisos são
 * emitidos por gatilho nas tabelas de conversa e mensagem e entregues pelo banco só após a
 * confirmação da transação, a <strong>todas</strong> as instâncias conectadas — é assim que cada
 * instância atrás do ALB recebe as mudanças feitas pelas outras, sem serviço adicional.
 * <p>
 * Conteúdo do aviso: somente ids (assessoria, conversa, credor), nunca dados pessoais. Falha na
 * conexão: nova tentativa com espera crescente (até 1 min) e aviso de ressincronização aos
 * assinantes quando a escuta volta. Desligado por {@code app.communication.realtime.enabled=false}.
 */
@Component
class PostgresInboxListener implements SmartLifecycle {

    static final String CHANNEL = "communication_inbox";
    private static final Logger LOG = LoggerFactory.getLogger(PostgresInboxListener.class);
    private static final int POLL_MILLIS = 10_000;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    private final DataSourceProperties dataSource;
    private final InboxChangeSink sink;
    private final CommunicationProperties.Realtime properties;
    private final JsonCodec json;
    private final MeterRegistry registry;
    private volatile boolean running;
    private volatile Thread worker;
    private volatile Connection connection;

    PostgresInboxListener(DataSourceProperties dataSource, InboxChangeSink sink, CommunicationProperties properties,
                          JsonCodec json, MeterRegistry registry) {
        this.dataSource = dataSource;
        this.sink = sink;
        this.properties = properties.realtime();
        this.json = json;
        this.registry = registry;
    }

    @Override
    public void start() {
        if (!properties.enabled() || running) {
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("inbox-listener").daemon().start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        closeQuietly();
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Depois do pool e do Flyway; para antes deles. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    private void loop() {
        Duration backoff = properties.reconnectDelay();
        boolean reconnecting = false;
        while (running) {
            try {
                Connection c = open();
                if (reconnecting) {
                    // avisos emitidos enquanto a escuta estava fora se perderam: assinantes recarregam
                    sink.resync();
                    LOG.atInfo().log("Escuta da caixa de entrada restabelecida");
                }
                backoff = properties.reconnectDelay();
                reconnecting = true;
                PGConnection pg = c.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(POLL_MILLIS);
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            dispatch(n.getParameter());
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    break;
                }
                registry.counter("communication.realtime.listener.failures").increment();
                LOG.atWarn().addKeyValue("retryInMs", backoff.toMillis()).setCause(e)
                        .log("Falha na escuta de avisos da caixa de entrada; nova tentativa");
                closeQuietly();
                sleep(backoff);
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
        closeQuietly();
    }

    private Connection open() throws SQLException {
        Properties info = new Properties();
        info.setProperty("user", dataSource.determineUsername());
        String password = dataSource.determinePassword();
        if (password != null) {
            info.setProperty("password", password);
        }
        info.setProperty("ApplicationName", "gestor-cobranca-inbox-listener");
        // detecção de conexão morta (failover do RDS, rede) sem depender de tráfego
        info.setProperty("tcpKeepAlive", "true");
        Connection c = DriverManager.getConnection(dataSource.determineUrl(), info);
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("LISTEN " + CHANNEL);
        }
        connection = c;
        return c;
    }

    /** Aviso publicado pelo gatilho: {@code {"t": tenant, "c": conversa, "k": credor|null}}. */
    record Payload(String t, String c, String k) {
    }

    void dispatch(String payload) {
        try {
            Payload p = json.fromJson(payload, Payload.class);
            sink.changed(UUID.fromString(p.t()), UUID.fromString(p.c()), p.k() == null ? null : UUID.fromString(p.k()));
        } catch (RuntimeException e) {
            registry.counter("communication.realtime.listener.malformed").increment();
            LOG.atWarn().setCause(e).log("Aviso da caixa de entrada malformado ignorado");
        }
    }

    private void closeQuietly() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // conexão já perdida
            }
        }
    }

    private void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
