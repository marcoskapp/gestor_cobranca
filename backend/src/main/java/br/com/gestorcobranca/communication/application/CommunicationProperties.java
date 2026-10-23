package br.com.gestorcobranca.communication.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros do módulo communication ({@code app.communication.*}).
 *
 * @param sendMaxAttempts tentativas de envio por mensagem antes de FAILED/DEAD (falhas transitórias)
 * @param retentionCron   descarte diário do conteúdo de mensagens (política MESSAGE_CONTENT do compliance)
 * @param retentionBatch  mensagens por execução e assessoria
 * @param webhook         proteção dos webhooks
 * @param whatsapp        WhatsApp Cloud API
 * @param sandbox         provedor simulado (local/teste)
 * @param realtime        avisos em tempo real da caixa de entrada (SSE + LISTEN/NOTIFY)
 */
@ConfigurationProperties(prefix = "app.communication")
public record CommunicationProperties(Integer sendMaxAttempts, String retentionCron, Integer retentionBatch,
                                      Webhook webhook, WhatsApp whatsapp, Sandbox sandbox, Realtime realtime) {

    public CommunicationProperties {
        sendMaxAttempts = sendMaxAttempts == null ? 6 : sendMaxAttempts;
        retentionCron = retentionCron == null ? "0 40 2 * * *" : retentionCron;
        retentionBatch = retentionBatch == null ? 500 : retentionBatch;
        webhook = webhook == null ? new Webhook(null, null, null, null) : webhook;
        whatsapp = whatsapp == null ? new WhatsApp(null, null, null) : whatsapp;
        sandbox = sandbox == null ? new Sandbox(false, null) : sandbox;
        realtime = realtime == null ? new Realtime(null, null, null, null, null, null, null) : realtime;
    }

    /**
     * @param maxBodyBytes      tamanho máximo do corpo (413 acima)
     * @param requestsPerMinute limite por rota e IP de origem, por instância (429 acima)
     * @param maxEventAge       eventos mais antigos são descartados (replay); também o prazo dos recibos
     * @param maxClockSkew      tolerância para eventos com instante no futuro
     */
    public record Webhook(Integer maxBodyBytes, Integer requestsPerMinute, Duration maxEventAge, Duration maxClockSkew) {

        public Webhook {
            maxBodyBytes = maxBodyBytes == null ? 256 * 1024 : maxBodyBytes;
            requestsPerMinute = requestsPerMinute == null ? 600 : requestsPerMinute;
            maxEventAge = maxEventAge == null ? Duration.ofDays(7) : maxEventAge;
            maxClockSkew = maxClockSkew == null ? Duration.ofMinutes(5) : maxClockSkew;
        }
    }

    /** @param apiBaseUrl base da Graph API (versão no caminho) */
    public record WhatsApp(String apiBaseUrl, Duration connectTimeout, Duration readTimeout) {

        public WhatsApp {
            apiBaseUrl = apiBaseUrl == null ? "https://graph.facebook.com/v21.0" : apiBaseUrl;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        }
    }

    /** @param deliveryDelay atraso da entrega simulada (DELIVERED e READ) */
    public record Sandbox(boolean enabled, Duration deliveryDelay) {

        public Sandbox {
            deliveryDelay = deliveryDelay == null ? Duration.ZERO : deliveryDelay;
        }
    }

    /**
     * Tempo real da caixa de entrada: cada instância escuta o canal {@code communication_inbox} do
     * PostgreSQL (LISTEN/NOTIFY, sem serviço novo da AWS) e repassa avisos às conexões SSE locais.
     *
     * @param enabled            liga o ouvinte e o endpoint de stream (desligado: só polling)
     * @param heartbeat          comentário periódico na conexão (abaixo do idle timeout do ALB, 60 s) e
     *                           revalidação da sessão
     * @param maxStreamDuration  duração máxima de uma conexão (o cliente reconecta com o token vigente)
     * @param maxStreamsPerUser  conexões simultâneas por usuário (abas) por instância
     * @param maxStreams         conexões simultâneas por instância (acima: 503 e o cliente usa polling)
     * @param reconnectDelay     espera inicial para reabrir a conexão de escuta após falha (dobra até 1 min)
     * @param revalidation       intervalo de revalidação do token e da sessão de cada conexão aberta
     */
    public record Realtime(Boolean enabled, Duration heartbeat, Duration maxStreamDuration, Integer maxStreamsPerUser,
                           Integer maxStreams, Duration reconnectDelay, Duration revalidation) {

        public Realtime {
            enabled = enabled == null || enabled;
            heartbeat = heartbeat == null ? Duration.ofSeconds(20) : heartbeat;
            maxStreamDuration = maxStreamDuration == null ? Duration.ofMinutes(5) : maxStreamDuration;
            maxStreamsPerUser = maxStreamsPerUser == null ? 5 : maxStreamsPerUser;
            maxStreams = maxStreams == null ? 2000 : maxStreams;
            reconnectDelay = reconnectDelay == null ? Duration.ofSeconds(5) : reconnectDelay;
            revalidation = revalidation == null ? Duration.ofMinutes(1) : revalidation;
        }
    }
}
