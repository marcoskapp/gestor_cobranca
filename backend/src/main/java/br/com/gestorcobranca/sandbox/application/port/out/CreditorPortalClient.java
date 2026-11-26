package br.com.gestorcobranca.sandbox.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A própria API HTTP do portal do credor ({@code /api/creditor/v1}), chamada pela carga do sandbox como a pessoa do
 * credor faria pelo navegador: ativação do convite, login em duas etapas (senha e código do autenticador) e as ações,
 * sempre com o token de uma sessão real aberta pelo servidor e com a {@code Origin} da aplicação (RS-83). A carga nunca
 * monta o ator do credor: quem decide quem é a pessoa, o credor e as permissões é o servidor, a cada requisição.
 * <p>
 * Os códigos do autenticador são pedidos por {@link Supplier} a cada tentativa (o fornecedor espera um passo de 30 s
 * novo): um 429 com {@code Retry-After} é esperado e a nova tentativa usa outro código, nunca o recusado (RS-89).
 * Nenhum token, código ou segredo vai para o log nem para a mensagem de erro.
 */
public interface CreditorPortalClient {

    /** {@code POST /auth/activation/start}: o segredo aleatório do autenticador, gerado pelo servidor. */
    Enrollment startActivation(String invitationToken);

    /** {@code POST /auth/activation/complete}: senha + código; a sessão nasce aqui. */
    Session completeActivation(String invitationToken, String password, Supplier<String> code);

    /** {@code POST /auth/login} e {@code /auth/mfa}; o código só é pedido depois da senha aceita. */
    Session login(String tenant, String email, String password, Supplier<String> code);

    /** {@code POST /session/logout}. */
    void logout(Session session);

    /** {@code GET /statements}: id e situação dos demonstrativos que o credor vê. */
    List<Item> statements(Session session);

    /** {@code GET /statements/{id}}: SHA-256 dos arquivos que a pessoa "viu" (o aceite confere os mesmos). */
    List<String> statementFileHashes(Session session, UUID statementId);

    /** {@code POST /statements/{id}/acknowledge} com o código do autenticador (nova verificação na própria ação). */
    void acknowledgeStatement(Session session, UUID statementId, String note, List<String> fileSha256,
                              Supplier<String> code);

    /** Pedido da assessoria que aguarda resposta na contestação ({@code GET /disputes/{id}}), se houver. */
    Optional<UUID> awaitingRequest(Session session, UUID disputeId);

    /** {@code POST /disputes/{id}/responses} (multipart: {@code response} JSON e um documento fictício). */
    void respondDispute(Session session, UUID disputeId, UUID requestId, String position, String note, Document document);

    /** {@code GET /payments}: id e situação dos pagamentos informados pelo credor no portal. */
    List<Item> reportedPayments(Session session);

    /** {@code GET /payments/titles?number=}: o título existe no credor da sessão, com saldo e sem acordo misturado. */
    boolean titleAvailable(Session session, String titleNumber);

    /** {@code POST /payments} (multipart, {@code Idempotency-Key}) com o código do autenticador; devolve o id. */
    UUID reportPayment(Session session, PaymentReport report, Document evidence, String idempotencyKey,
                       Supplier<String> code);

    record Enrollment(String secret) {

        @Override
        public String toString() {
            return "Enrollment[secret=***]";
        }
    }

    record Session(String accessToken) {

        @Override
        public String toString() {
            return "Session[accessToken=***]";
        }
    }

    record Item(UUID id, String status) {
    }

    record Document(String fileName, String contentType, byte[] content) {
    }

    record PaymentReport(List<String> titleNumbers, BigDecimal amount, LocalDate paidOn, String method, String reference,
                         String note) {
    }

    /**
     * Recusa da API (situação HTTP e o código estável do problema, sem o corpo: nada de token ou dado pessoal na
     * mensagem).
     */
    final class CallFailedException extends RuntimeException {

        private final int status;
        private final String code;

        public CallFailedException(String operation, int status, String code) {
            super("portal do credor recusou " + operation + ": HTTP " + status + (code == null ? "" : " (" + code + ")"));
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
