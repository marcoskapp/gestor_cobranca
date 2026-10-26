package br.com.gestorcobranca.telephony.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parâmetros do módulo telephony ({@code app.telephony.*}).
 *
 * @param dialMaxAttempts      tentativas do job de discagem (falhas transitórias do provedor) antes de FAILED
 * @param tabulationDeadline   prazo do pós-atendimento: sem tabulação do operador, a chamada é tabulada automaticamente
 * @param presenceTimeout      operador sem sinal do cliente ({@code heartbeat}) além deste prazo sai automaticamente
 * @param staleCallAfter       chamada em andamento sem evento do provedor além deste prazo é encerrada (conciliação)
 * @param retentionCron        descarte diário de gravações (política CALL_RECORDINGS do compliance)
 * @param retentionBatch       gravações por lote e assessoria
 * @param recordingMaxBytes    tamanho máximo do arquivo de gravação copiado do provedor
 * @param dialer               ritmo do discador
 * @param webhook              proteção dos webhooks
 * @param sandbox              provedor simulado (local/teste)
 * @param routing              roteamento entre contas (contingência)
 * @param homologation         provedores com contrato a confirmar liberados para discar (homologação)
 * @param alvaria              adaptador da Alvaria CX
 */
@ConfigurationProperties(prefix = "app.telephony")
public record TelephonyProperties(Integer dialMaxAttempts, Duration tabulationDeadline, Duration presenceTimeout,
                                  Duration staleCallAfter, String retentionCron, Integer retentionBatch,
                                  Long recordingMaxBytes, Dialer dialer, Webhook webhook, Sandbox sandbox,
                                  Routing routing, Homologation homologation, Alvaria alvaria) {

    public TelephonyProperties {
        dialMaxAttempts = dialMaxAttempts == null ? 3 : dialMaxAttempts;
        tabulationDeadline = tabulationDeadline == null ? Duration.ofMinutes(15) : tabulationDeadline;
        presenceTimeout = presenceTimeout == null ? Duration.ofSeconds(90) : presenceTimeout;
        staleCallAfter = staleCallAfter == null ? Duration.ofHours(4) : staleCallAfter;
        retentionCron = retentionCron == null ? "0 50 2 * * *" : retentionCron;
        retentionBatch = retentionBatch == null ? 200 : retentionBatch;
        recordingMaxBytes = recordingMaxBytes == null ? 200L * 1024 * 1024 : recordingMaxBytes;
        dialer = dialer == null ? new Dialer(null, null, null, null) : dialer;
        webhook = webhook == null ? new Webhook(null, null, null, null) : webhook;
        sandbox = sandbox == null ? new Sandbox(false, null) : sandbox;
        routing = routing == null ? new Routing(null) : routing;
        homologation = homologation == null ? new Homologation(null) : homologation;
        alvaria = alvaria == null ? new Alvaria(null, null, null, false) : alvaria;
    }

    /**
     * @param fallbackEnabled desvia a discagem para a conta de contingência da regra quando a principal
     *                        falha de forma transitória sem ter criado a chamada (uma vez por chamada)
     */
    public record Routing(Boolean fallbackEnabled) {

        public Routing {
            fallbackEnabled = fallbackEnabled == null ? Boolean.TRUE : fallbackEnabled;
        }
    }

    /**
     * @param providers provedores cujo adaptador ainda aguarda a confirmação do contrato
     *                  ({@code CONTRACT_PENDING}) e que podem discar neste ambiente — somente no
     *                  ambiente de homologação, com o ambiente de testes do fornecedor
     */
    public record Homologation(Set<String> providers) {

        public Homologation {
            providers = providers == null ? Set.of() : providers.stream().map(p -> p.trim().toUpperCase(Locale.ROOT))
                    .filter(p -> !p.isEmpty()).collect(Collectors.toUnmodifiableSet());
        }

        public boolean allows(String provider) {
            return providers.contains(provider);
        }
    }

    /**
     * @param connectTimeout       tempo de conexão com a API da Alvaria
     * @param readTimeout          tempo de leitura
     * @param allowedHostSuffixes  domínios aceitos no {@code apiBaseUrl} das contas (proteção contra
     *                             SSRF: a assessoria informa o endereço da própria instância)
     * @param allowInsecureHttp    aceita {@code http://} (somente testes com servidor local; recusado no
     *                             perfil {@code prod})
     */
    public record Alvaria(Duration connectTimeout, Duration readTimeout, List<String> allowedHostSuffixes,
                          boolean allowInsecureHttp) {

        public Alvaria {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
            allowedHostSuffixes = allowedHostSuffixes == null || allowedHostSuffixes.isEmpty()
                    ? List.of(".alvaria.com") : List.copyOf(allowedHostSuffixes);
        }
    }

    /**
     * @param tickInterval  intervalo entre ciclos de cada campanha ativa
     * @param maxPerTick    teto de novas chamadas por ciclo e campanha
     * @param loadPageSize  casos lidos por página ao montar a lista a partir da fila
     * @param maxEntries    teto de casos por carga de lista
     */
    public record Dialer(Duration tickInterval, Integer maxPerTick, Integer loadPageSize, Integer maxEntries) {

        public Dialer {
            tickInterval = tickInterval == null ? Duration.ofSeconds(3) : tickInterval;
            maxPerTick = maxPerTick == null ? 50 : maxPerTick;
            loadPageSize = loadPageSize == null ? 200 : loadPageSize;
            maxEntries = maxEntries == null ? 50_000 : maxEntries;
        }
    }

    /**
     * @param maxBodyBytes      tamanho máximo do corpo (413 acima)
     * @param requestsPerMinute limite por rota e IP de origem, por instância (429 acima)
     * @param maxEventAge       eventos mais antigos são descartados (replay); também o prazo dos recibos
     * @param maxClockSkew      tolerância para eventos/assinaturas com instante no futuro
     */
    public record Webhook(Integer maxBodyBytes, Integer requestsPerMinute, Duration maxEventAge, Duration maxClockSkew) {

        public Webhook {
            maxBodyBytes = maxBodyBytes == null ? 64 * 1024 : maxBodyBytes;
            requestsPerMinute = requestsPerMinute == null ? 1200 : requestsPerMinute;
            maxEventAge = maxEventAge == null ? Duration.ofDays(2) : maxEventAge;
            maxClockSkew = maxClockSkew == null ? Duration.ofMinutes(5) : maxClockSkew;
        }
    }

    /**
     * @param autoProgress simula o andamento das chamadas (toque e atendimento por pessoa) sem
     *                     intervenção; desligado nos testes, que conduzem os eventos explicitamente
     */
    public record Sandbox(boolean enabled, Boolean autoProgress) {

        public Sandbox {
            autoProgress = autoProgress == null ? Boolean.TRUE : autoProgress;
        }
    }
}
