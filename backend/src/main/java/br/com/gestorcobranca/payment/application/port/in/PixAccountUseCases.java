package br.com.gestorcobranca.payment.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contas Pix (PSP) da assessoria e dos credores ({@code INTEGRATION_MANAGE}). Segredos e
 * certificado são gravados criptografados e nunca devolvidos; a rotação substitui o conjunto inteiro
 * (o certificado só é trocado quando informado). Contas da assessoria (sem credor) exigem acesso a
 * todos os credores. O provedor simulado ({@code SANDBOX}) só existe em ambiente local/teste.
 */
public interface PixAccountUseCases {

    AccountView create(CreateAccountCommand command);

    AccountView update(UUID accountId, UpdateAccountCommand command);

    AccountView rotateCredentials(UUID accountId, CredentialsCommand command);

    /** Configura no PSP a URL do webhook desta conta ({@code PUT /webhook/{chave}}). */
    AccountView registerWebhook(UUID accountId);

    AccountView activate(UUID accountId);

    AccountView deactivate(UUID accountId);

    AccountView get(UUID accountId);

    List<AccountView> list();

    /**
     * Modos de autenticação do webhook disponíveis neste ambiente, para a tela de cadastro: o mTLS só
     * existe quando o balanceador verifica o certificado do PSP ({@code app.payment.webhook.mtls-enabled},
     * decisão 316); senão o padrão é HMAC. Não expõe nada além desses dois valores.
     */
    WebhookOptions webhookOptions();

    /**
     * @param mtlsAvailable o listener do ALB verifica o certificado do cliente (mutual TLS em modo verify)
     * @param defaultAuth   modo sugerido no cadastro: MTLS quando disponível, senão HMAC
     */
    record WebhookOptions(boolean mtlsAvailable, String defaultAuth) {
    }

    /**
     * @param provider            BACEN_API ou SANDBOX
     * @param webhookAuth         MTLS (padrão da API Pix) ou HMAC
     * @param webhookFingerprints SHA-256 (hex) dos certificados do PSP aceitos no webhook com mTLS
     */
    record CreateAccountCommand(UUID creditorId, String name, String provider, String pixKey, String apiBaseUrl,
                                String tokenUrl, String webhookAuth, List<String> webhookFingerprints,
                                CredentialsCommand credentials) {
    }

    record UpdateAccountCommand(String name, String pixKey, String apiBaseUrl, String tokenUrl, String webhookAuth,
                                List<String> webhookFingerprints) {
    }

    /**
     * @param certificateBase64   certificado de cliente mTLS do PSP em PKCS#12 (Base64); nulo mantém o atual
     * @param certificatePassword senha do PKCS#12
     * @param webhookSecret       segredo HMAC das notificações (modo HMAC)
     */
    record CredentialsCommand(String clientId, String clientSecret, String webhookSecret, String certificateBase64,
                              String certificatePassword) {

        @Override
        public String toString() {
            return "CredentialsCommand[***]";
        }
    }

    /**
     * @param webhookUrl          URL a configurar no PSP (o PSP acrescenta {@code /pix})
     * @param credentialKeys      nomes dos segredos gravados (nunca os valores)
     * @param certificateSha256   impressão digital do certificado de cliente
     */
    record AccountView(UUID id, UUID creditorId, String name, String provider, String pixKey, String apiBaseUrl,
                       String tokenUrl, String webhookAuth, List<String> webhookFingerprints, String webhookUrl,
                       List<String> credentialKeys, String certificateSha256, Instant certificateExpiresAt,
                       Instant credentialsUpdatedAt, Instant webhookRegisteredAt, boolean active, long version) {
    }
}
