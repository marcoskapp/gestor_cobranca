package br.com.gestorcobranca.payment.application.port.in;

/**
 * <strong>Uso interno do adaptador web de webhooks.</strong> Resolve a conta pelo {@code routingKey},
 * autentica (mTLS com certificado do PSP fixado na conta, ou HMAC do corpo) antes de qualquer
 * processamento — recusa → {@code WEBHOOK_REJECTED} no log de acesso —, descarta reentregas do mesmo
 * corpo e notificações fora da janela de replay e agenda a confirmação de cada Pix por consulta ao PSP
 * (idempotente pelo {@code endToEndId}).
 */
public interface PixWebhookUseCases {

    /**
     * @param signatureHeader   assinatura HMAC ({@code sha256=<hex>}), no modo HMAC
     * @param clientCertificate certificado do PSP repassado pelo ALB (mTLS), no modo MTLS
     */
    Outcome receive(String routingKey, String signatureHeader, String clientCertificate, byte[] body, String clientIp);

    enum Outcome {
        ACCEPTED, DUPLICATE, UNKNOWN_ROUTE, UNAUTHENTICATED, MALFORMED
    }
}
