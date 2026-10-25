package br.com.gestorcobranca.payment.application;

import br.com.gestorcobranca.payment.domain.model.PaymentSettings;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Parâmetros do módulo payment ({@code app.payment.*}).
 *
 * @param defaults                  padrões da assessoria enquanto ela não grava os seus
 * @param webhook                   proteção dos webhooks Pix
 * @param pix                       cliente da API Pix (BACEN) dos PSPs
 * @param evidence                  evidências de pagamentos externos
 * @param statement                 extratos/arquivos de retorno
 * @param expirationCheckInterval   varredura de instruções vencidas
 * @param sandbox                   PSP simulado (local/teste; recusado no perfil prod)
 * @param externalReview            limites acumulados de pagamentos externos sem segunda pessoa
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(Defaults defaults, Webhook webhook, Pix pix, Evidence evidence, Statement statement,
                                Duration expirationCheckInterval, Sandbox sandbox, ExternalReview externalReview) {

    public PaymentProperties {
        defaults = defaults == null ? new Defaults(null, null, null, null) : defaults;
        webhook = webhook == null ? new Webhook(null, null, null, null, null, null) : webhook;
        pix = pix == null ? new Pix(null, null, null, null) : pix;
        evidence = evidence == null ? new Evidence(null) : evidence;
        statement = statement == null ? new Statement(null, null) : statement;
        expirationCheckInterval = expirationCheckInterval == null ? Duration.ofMinutes(15) : expirationCheckInterval;
        sandbox = sandbox == null ? new Sandbox(false) : sandbox;
        externalReview = externalReview == null ? new ExternalReview(null, null, null) : externalReview;
    }

    /**
     * Controle acumulado dos pagamentos externos (revisão de segurança A5): além do limite por pagamento
     * da assessoria, a soma confirmada <em>sem revisão</em> na janela não passa destes múltiplos dele; acima,
     * exige aprovação de outra pessoa. Relativos ao limite da assessoria para respeitar a configuração dela.
     *
     * @param window            janela de acumulação (padrão 24 h)
     * @param destinationFactor múltiplo do limite por pagamento para a soma por devedor no credor (padrão 1)
     * @param informerFactor    múltiplo do limite por pagamento para a soma por usuário que informa (padrão 4)
     */
    public record ExternalReview(Duration window, BigDecimal destinationFactor, BigDecimal informerFactor) {

        public ExternalReview {
            window = window == null ? Duration.ofHours(24) : window;
            destinationFactor = destinationFactor == null ? BigDecimal.ONE : destinationFactor;
            informerFactor = informerFactor == null ? new BigDecimal("4") : informerFactor;
            if (window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("app.payment.external-review.window deve ser positiva");
            }
            if (destinationFactor.compareTo(BigDecimal.ONE) < 0 || informerFactor.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("app.payment.external-review: fatores devem ser >= 1");
            }
        }
    }

    public record Defaults(BigDecimal externalReviewThreshold, Integer pixExpirationSeconds, Integer pixDueValidityDays,
                           Integer reconciliationDateToleranceDays) {

        public Defaults {
            externalReviewThreshold = externalReviewThreshold == null ? new BigDecimal("500.00") : externalReviewThreshold;
            pixExpirationSeconds = pixExpirationSeconds == null ? 86_400 : pixExpirationSeconds;
            pixDueValidityDays = pixDueValidityDays == null ? 30 : pixDueValidityDays;
            reconciliationDateToleranceDays = reconciliationDateToleranceDays == null ? 3 : reconciliationDateToleranceDays;
        }

        public PaymentSettings.Values values() {
            return new PaymentSettings.Values(Money.of(externalReviewThreshold), pixExpirationSeconds, pixDueValidityDays,
                    reconciliationDateToleranceDays);
        }
    }

    /**
     * @param publicBaseUrl           URL pública da aplicação (ALB) usada para montar a URL do webhook no PSP
     * @param maxBodyBytes            tamanho máximo do corpo (413 acima)
     * @param requestsPerMinute       limite por rota e IP de origem, por instância (429 acima)
     * @param maxEventAge             notificações com horário mais antigo são descartadas (replay); prazo dos recibos
     * @param maxClockSkew            tolerância para horário no futuro
     * @param clientCertificateHeader cabeçalho em que o ALB (mTLS em modo verify) repassa o certificado do PSP
     */
    public record Webhook(String publicBaseUrl, Integer maxBodyBytes, Integer requestsPerMinute, Duration maxEventAge,
                          Duration maxClockSkew, String clientCertificateHeader) {

        public Webhook {
            publicBaseUrl = publicBaseUrl == null ? "http://localhost:8080" : publicBaseUrl;
            maxBodyBytes = maxBodyBytes == null ? 256 * 1024 : maxBodyBytes;
            requestsPerMinute = requestsPerMinute == null ? 600 : requestsPerMinute;
            maxEventAge = maxEventAge == null ? Duration.ofDays(7) : maxEventAge;
            maxClockSkew = maxClockSkew == null ? Duration.ofMinutes(5) : maxClockSkew;
            clientCertificateHeader = clientCertificateHeader == null ? "X-Amzn-Mtls-Clientcert-Leaf"
                    : clientCertificateHeader;
        }
    }

    /**
     * @param tokenRefreshMargin renova o token OAuth2 quando faltar menos que isso para expirar
     * @param scope              escopos OAuth2 pedidos ao PSP
     */
    public record Pix(Duration connectTimeout, Duration readTimeout, Duration tokenRefreshMargin, String scope) {

        public Pix {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
            tokenRefreshMargin = tokenRefreshMargin == null ? Duration.ofSeconds(60) : tokenRefreshMargin;
            scope = scope == null ? "cob.write cob.read cobv.write cobv.read pix.read webhook.write webhook.read" : scope;
        }
    }

    public record Evidence(Long maxSizeBytes) {

        public Evidence {
            maxSizeBytes = maxSizeBytes == null ? 10L * 1024 * 1024 : maxSizeBytes;
        }
    }

    public record Statement(Long maxSizeBytes, Integer maxLines) {

        public Statement {
            maxSizeBytes = maxSizeBytes == null ? 10L * 1024 * 1024 : maxSizeBytes;
            maxLines = maxLines == null ? 50_000 : maxLines;
        }
    }

    public record Sandbox(boolean enabled) {
    }
}
