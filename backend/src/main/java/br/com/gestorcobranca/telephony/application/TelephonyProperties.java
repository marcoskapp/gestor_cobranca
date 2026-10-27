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
 * @param twilio               adaptador da Twilio (API pública)
 * @param plivo                adaptador da Plivo (API pública)
 * @param messages             textos falados pelos provedores com síntese de voz (Twilio, Plivo)
 */
@ConfigurationProperties(prefix = "app.telephony")
public record TelephonyProperties(Integer dialMaxAttempts, Duration tabulationDeadline, Duration presenceTimeout,
                                  Duration staleCallAfter, String retentionCron, Integer retentionBatch,
                                  Long recordingMaxBytes, Dialer dialer, Webhook webhook, Sandbox sandbox,
                                  Routing routing, Homologation homologation, Alvaria alvaria, Twilio twilio,
                                  Plivo plivo, Messages messages) {

    public TelephonyProperties {
        dialMaxAttempts = dialMaxAttempts == null ? 3 : dialMaxAttempts;
        tabulationDeadline = tabulationDeadline == null ? Duration.ofMinutes(15) : tabulationDeadline;
        presenceTimeout = presenceTimeout == null ? Duration.ofSeconds(90) : presenceTimeout;
        staleCallAfter = staleCallAfter == null ? Duration.ofHours(4) : staleCallAfter;
        retentionCron = retentionCron == null ? "0 50 2 * * *" : retentionCron;
        retentionBatch = retentionBatch == null ? 200 : retentionBatch;
        recordingMaxBytes = recordingMaxBytes == null ? 200L * 1024 * 1024 : recordingMaxBytes;
        dialer = dialer == null ? new Dialer(null, null, null, null) : dialer;
        webhook = webhook == null ? new Webhook(null, null, null, null, null) : webhook;
        sandbox = sandbox == null ? new Sandbox(false, null) : sandbox;
        routing = routing == null ? new Routing(null) : routing;
        homologation = homologation == null ? new Homologation(null) : homologation;
        alvaria = alvaria == null ? new Alvaria(null, null, null, false) : alvaria;
        twilio = twilio == null ? new Twilio(null, null, null, false, null, null, null, null, null, null, null) : twilio;
        plivo = plivo == null ? new Plivo(null, null, null, null, false, null, null, null, null, null) : plivo;
        messages = messages == null ? new Messages(null, null) : messages;
    }

    /**
     * Twilio Programmable Voice. A URL da API é fixa por ambiente (não vem da conta): a lista de
     * hosts permitidos protege contra SSRF e é conferida na subida.
     *
     * @param enabled            oferece o provedor neste ambiente (desligado: contas não são criadas nem ativadas)
     * @param apiBaseUrl         API REST ({@code https://api.twilio.com})
     * @param allowedHosts       hosts aceitos em {@code apiBaseUrl}
     * @param allowInsecureHttp  aceita {@code http://} (somente testes com servidor local; recusado no perfil {@code prod})
     * @param amdTimeout         {@code MachineDetectionTimeout} (3–59 s; esgotado = {@code unknown}, tratado como pessoa)
     * @param sayVoice           voz do {@code <Say>} (ex.: {@code Polly.Camila}; conferir no console)
     * @param sayLanguage        idioma do {@code <Say>}
     * @param softphoneTokenTtl  validade do token do SDK de voz do navegador (máx. 24 h)
     * @param softphoneEdge      borda do SDK de voz ({@code roaming} = a mais próxima; {@code sao-paulo}…)
     */
    public record Twilio(Boolean enabled, String apiBaseUrl, List<String> allowedHosts, boolean allowInsecureHttp,
                         Duration connectTimeout, Duration readTimeout, Duration amdTimeout, String sayVoice,
                         String sayLanguage, Duration softphoneTokenTtl, String softphoneEdge) {

        public Twilio {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? "https://api.twilio.com" : stripSlash(apiBaseUrl);
            allowedHosts = allowedHosts == null || allowedHosts.isEmpty() ? List.of("api.twilio.com") : List.copyOf(allowedHosts);
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
            amdTimeout = amdTimeout == null ? Duration.ofSeconds(8) : amdTimeout;
            sayVoice = sayVoice == null || sayVoice.isBlank() ? "Polly.Camila" : sayVoice.trim();
            sayLanguage = sayLanguage == null || sayLanguage.isBlank() ? "pt-BR" : sayLanguage.trim();
            softphoneTokenTtl = softphoneTokenTtl == null ? Duration.ofHours(1) : softphoneTokenTtl;
            softphoneEdge = softphoneEdge == null || softphoneEdge.isBlank() ? "roaming" : softphoneEdge.trim();
        }
    }

    /**
     * Plivo Voice. URL da API fixa por ambiente; downloads de gravação só dos hosts permitidos.
     *
     * @param enabled           oferece o provedor neste ambiente
     * @param apiBaseUrl        API REST ({@code https://api.plivo.com})
     * @param allowedHosts      hosts aceitos em {@code apiBaseUrl}
     * @param recordingHosts    hosts aceitos no {@code recording_url} devolvido pela Plivo (download do áudio)
     * @param allowInsecureHttp aceita {@code http://} (somente testes; recusado no perfil {@code prod})
     * @param amdTime           {@code machine_detection_time} (2–10 s): sem veredito de máquina nesse prazo, a
     *                          chamada é tratada como atendida por pessoa
     * @param speakVoice        voz do {@code <Speak>} ({@code WOMAN}/{@code MAN})
     * @param speakLanguage     idioma do {@code <Speak>}
     */
    public record Plivo(Boolean enabled, String apiBaseUrl, List<String> allowedHosts, List<String> recordingHosts,
                        boolean allowInsecureHttp, Duration connectTimeout, Duration readTimeout, Duration amdTime,
                        String speakVoice, String speakLanguage) {

        public Plivo {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? "https://api.plivo.com" : stripSlash(apiBaseUrl);
            allowedHosts = allowedHosts == null || allowedHosts.isEmpty() ? List.of("api.plivo.com") : List.copyOf(allowedHosts);
            recordingHosts = recordingHosts == null || recordingHosts.isEmpty() ? List.of("media.plivo.com")
                    : List.copyOf(recordingHosts);
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
            amdTime = amdTime == null ? Duration.ofSeconds(5) : amdTime;
            speakVoice = speakVoice == null || speakVoice.isBlank() ? "WOMAN" : speakVoice.trim();
            speakLanguage = speakLanguage == null || speakLanguage.isBlank() ? "pt-BR" : speakLanguage.trim();
        }
    }

    /**
     * Textos falados pela síntese de voz do provedor (validar com o jurídico antes da produção).
     *
     * @param recordingNotice aviso de gravação ao devedor (LGPD art. 9º)
     * @param abandonMessage  mensagem informativa quando não há operador livre (abandono)
     */
    public record Messages(String recordingNotice, String abandonMessage) {

        public Messages {
            recordingNotice = recordingNotice == null || recordingNotice.isBlank()
                    ? "Esta ligação pode ser gravada para sua segurança e para a qualidade do atendimento." : recordingNotice.trim();
            abandonMessage = abandonMessage == null || abandonMessage.isBlank()
                    ? "Olá. No momento todos os nossos atendentes estão ocupados. Retornaremos a sua ligação. Obrigado."
                    : abandonMessage.trim();
        }
    }

    private static String stripSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
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
     * @param publicBaseUrl     endereço público da aplicação (ALB) usado nas URLs de webhook entregues aos
     *                          provedores e na validação das assinaturas que cobrem a URL (Twilio, Plivo)
     */
    public record Webhook(Integer maxBodyBytes, Integer requestsPerMinute, Duration maxEventAge, Duration maxClockSkew,
                          String publicBaseUrl) {

        public Webhook {
            maxBodyBytes = maxBodyBytes == null ? 64 * 1024 : maxBodyBytes;
            requestsPerMinute = requestsPerMinute == null ? 6000 : requestsPerMinute;
            publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? "http://localhost:8080"
                    : stripSlash(publicBaseUrl);
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
