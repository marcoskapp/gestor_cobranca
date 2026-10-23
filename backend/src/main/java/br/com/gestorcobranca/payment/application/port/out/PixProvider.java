package br.com.gestorcobranca.payment.application.port.out;

import br.com.gestorcobranca.payment.domain.model.PixProviderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta da API Pix de um PSP, no contrato neutro da API Pix do BACEN (cobrança imediata {@code cob},
 * cobrança com vencimento {@code cobv}, consulta de Pix recebido e configuração de webhook). A conexão
 * (URL, credenciais OAuth2 e certificado mTLS) é por conta da assessoria/credor.
 * <p>
 * Erros: {@link ProviderException} com {@code transientFailure} para 429/5xx/rede (o job tenta de
 * novo); demais recusas são definitivas, com o código do PSP.
 */
public interface PixProvider {

    PixProviderType type();

    /** Cria (ou recria, idempotente pelo {@code txid}) a cobrança. */
    ChargeResult createCharge(PixConnection connection, ChargeRequest request);

    void cancelCharge(PixConnection connection, String txid, boolean due);

    /** Pix recebido, com devoluções; vazio se o PSP não o conhece. */
    Optional<PixTransaction> findPix(PixConnection connection, String endToEndId);

    void configureWebhook(PixConnection connection, String webhookUrl);

    /**
     * @param credentials       {@code clientId}, {@code clientSecret}, {@code certificatePassword}, {@code webhookSecret}
     * @param certificateBase64 PKCS#12 do certificado de cliente mTLS
     */
    record PixConnection(UUID accountId, String apiBaseUrl, String tokenUrl, String pixKey,
                         Map<String, String> credentials, String certificateBase64, Instant credentialsUpdatedAt) {

        @Override
        public String toString() {
            return "PixConnection[accountId=" + accountId + "]";
        }
    }

    /** Pagador (obrigatório na cobrança com vencimento). Documento só com dígitos. */
    record Payer(String document, String name) {

        @Override
        public String toString() {
            return "Payer[***]";
        }
    }

    /**
     * @param due                  cobrança com vencimento ({@code cobv})
     * @param expirationSeconds    validade da cobrança imediata
     * @param validityAfterDueDays dias de validade após o vencimento ({@code cobv})
     * @param description          texto exibido ao pagador (sem dados pessoais)
     */
    record ChargeRequest(String txid, boolean due, BigDecimal amount, Long expirationSeconds, LocalDate dueDate,
                         Integer validityAfterDueDays, Payer payer, String description) {
    }

    /** @param copyPaste BR Code ("pixCopiaECola") */
    record ChargeResult(String txid, Integer revision, String location, String copyPaste, String status) {
    }

    record PixTransaction(String endToEndId, String txid, BigDecimal amount, Instant paidAt, List<Refund> refunds) {

        public PixTransaction {
            refunds = refunds == null ? List.of() : List.copyOf(refunds);
        }
    }

    /** @param status EM_PROCESSAMENTO, DEVOLVIDO ou NAO_REALIZADO */
    record Refund(String id, String returnId, BigDecimal amount, String status, Instant at) {
    }

    class ProviderException extends RuntimeException {

        private final boolean transientFailure;
        private final String code;

        public ProviderException(String code, String message, boolean transientFailure, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.transientFailure = transientFailure;
        }

        public boolean isTransientFailure() {
            return transientFailure;
        }

        public String code() {
            return code;
        }
    }
}
