package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Conta Pix num PSP (API Pix do BACEN) da assessoria ({@code creditorId} nulo) ou de um credor.
 * Uma conta ativa por credor/assessoria.
 * <p>
 * Segredos (OAuth2 {@code client_id}/{@code client_secret}, segredo HMAC do webhook e senha do
 * certificado) ficam num JSON criptografado; o certificado de cliente mTLS (PKCS#12) fica em coluna
 * própria, também criptografada (AES-256-GCM, {@code FieldEncryptor}). Nada disso é devolvido pela
 * API nem vai para a auditoria — apenas a impressão digital (SHA-256) e a validade do certificado.
 * O {@code routingKey} (aleatório, 256 bits) identifica a conta no caminho do webhook.
 */
@Entity
@Table(name = "pix_account", schema = "payment")
public class PixAccount extends TenantScopedEntity {

    public static final int CREDENTIALS_MAX = 4000;
    public static final int CERTIFICATE_MAX = 22000;
    private static final Pattern CPF_KEY = Pattern.compile("\\d{11}|\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20, updatable = false)
    private PixProviderType provider;

    @Column(name = "pix_key", nullable = false, length = 77)
    private String pixKey;

    @Column(name = "api_base_url", nullable = false, length = 500)
    private String apiBaseUrl;

    @Column(name = "token_url", nullable = false, length = 500)
    private String tokenUrl;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "credentials_encrypted", length = 8000)
    private String credentials;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "certificate_encrypted", length = 32000)
    private String certificate;

    @Column(name = "certificate_sha256", length = 64)
    private String certificateSha256;

    @Column(name = "certificate_expires_at")
    private Instant certificateExpiresAt;

    @Column(name = "credentials_updated_at")
    private Instant credentialsUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "webhook_auth", nullable = false, length = 10)
    private WebhookAuthMode webhookAuth;

    /** Impressões digitais SHA-256 (hex) dos certificados do PSP aceitos no webhook (mTLS), separadas por vírgula. */
    @Column(name = "webhook_client_fingerprints", length = 1000)
    private String webhookClientFingerprints;

    /** Quem alterou por último URL, token ou autenticação do webhook; nulo depois da reativação por outra pessoa. */
    @Column(name = "endpoints_changed_by")
    private UUID endpointsChangedBy;

    @Column(name = "endpoints_changed_at")
    private Instant endpointsChangedAt;

    @Column(name = "webhook_registered_at")
    private Instant webhookRegisteredAt;

    @Column(name = "routing_key", nullable = false, length = 64, updatable = false)
    private String routingKey;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected PixAccount() {
    }

    public PixAccount(UUID creditorId, String name, PixProviderType provider, String pixKey, String apiBaseUrl,
                      String tokenUrl, WebhookAuthMode webhookAuth, List<String> webhookFingerprints, String routingKey) {
        Guard.require(provider != null, "pix_account.provider_required", "Provedor obrigatório");
        Guard.require(routingKey != null && routingKey.length() >= 32 && routingKey.length() <= 64,
                "pix_account.routing_key_invalid", "Chave de roteamento inválida");
        this.creditorId = creditorId;
        this.provider = provider;
        this.routingKey = routingKey;
        this.active = true;
        describe(name, pixKey, apiBaseUrl, tokenUrl);
        configureWebhook(webhookAuth, webhookFingerprints);
    }

    public void describe(String name, String pixKey, String apiBaseUrl, String tokenUrl) {
        Guard.require(name != null && !name.isBlank() && name.length() <= 120, "pix_account.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(pixKey != null && !pixKey.isBlank() && pixKey.trim().length() <= 77, "pix_account.pix_key_invalid",
                "Chave Pix do recebedor obrigatória (até 77 caracteres)");
        // conta de cobrança do credor/assessoria (pessoa jurídica): chave CPF (11 dígitos no DICT) é de
        // pessoa física e nunca recebe pagamentos de devedores
        Guard.require(!CPF_KEY.matcher(pixKey.trim()).matches(), "pix_account.pix_key_natural_person",
                "Chave Pix de pessoa física (CPF) não é aceita: use a chave da conta do credor ou da assessoria");
        this.name = name.trim();
        this.pixKey = pixKey.trim();
        this.apiBaseUrl = endpoint(apiBaseUrl, "pix_account.api_url_invalid", "URL base da API Pix");
        this.tokenUrl = endpoint(tokenUrl, "pix_account.token_url_invalid", "URL do token OAuth2");
    }

    /** Autenticação das notificações: mTLS exige ao menos um certificado do PSP fixado. */
    public void configureWebhook(WebhookAuthMode mode, List<String> fingerprints) {
        Guard.require(mode != null, "pix_account.webhook_auth_required", "Modo de autenticação do webhook obrigatório");
        List<String> normalized = fingerprints == null ? List.of() : fingerprints.stream()
                .filter(f -> f != null && !f.isBlank())
                .map(f -> f.replace(":", "").trim().toLowerCase(Locale.ROOT))
                .distinct().toList();
        Guard.require(normalized.stream().allMatch(f -> FINGERPRINT.matcher(f).matches()),
                "pix_account.fingerprint_invalid", "Impressão digital deve ser SHA-256 em hexadecimal");
        Guard.require(mode != WebhookAuthMode.MTLS || !normalized.isEmpty(), "pix_account.fingerprint_required",
                "Webhook com mTLS exige a impressão digital do certificado do PSP");
        Guard.require(normalized.size() <= 10, "pix_account.too_many_fingerprints", "Até 10 certificados do PSP");
        this.webhookAuth = mode;
        this.webhookClientFingerprints = normalized.isEmpty() ? null : String.join(",", normalized);
    }

    /** Substitui o conjunto de segredos (JSON já validado pelo serviço) e, se informado, o certificado. */
    public void replaceCredentials(String credentials, String certificate, String certificateSha256,
                                   Instant certificateExpiresAt, Instant now) {
        Guard.require(credentials == null || credentials.length() <= CREDENTIALS_MAX, "pix_account.credentials_too_long",
                "Credenciais muito longas");
        Guard.require(certificate == null || certificate.length() <= CERTIFICATE_MAX, "pix_account.certificate_too_long",
                "Certificado muito grande");
        this.credentials = credentials;
        if (certificate != null) {
            this.certificate = certificate;
            this.certificateSha256 = certificateSha256;
            this.certificateExpiresAt = certificateExpiresAt;
        }
        this.credentialsUpdatedAt = now;
    }

    public void webhookRegistered(Instant now) {
        this.webhookRegisteredAt = now;
    }

    public void activate() {
        this.active = true;
    }

    /**
     * Endereços da conta alterados (URL da API, do token ou autenticação do webhook): a conta fica inativa
     * até que <strong>outra pessoa</strong> a reative (segunda aprovação da troca de PSP).
     *
     * @return se a conta estava ativa e foi desativada
     */
    public boolean endpointsChanged(UUID by, Instant now) {
        Guard.require(by != null, "pix_account.actor_required", "Responsável obrigatório");
        this.endpointsChangedBy = by;
        this.endpointsChangedAt = now;
        boolean wasActive = active;
        this.active = false;
        return wasActive;
    }

    /** Reativação: depois de troca de endereços, só por pessoa diferente de quem trocou. */
    public void activateBy(UUID by) {
        Guard.requireState(endpointsChangedBy == null || !endpointsChangedBy.equals(by),
                "pix_account.activation_requires_second_person",
                "Os endereços da conta foram alterados por você; a reativação precisa de outra pessoa");
        this.endpointsChangedBy = null;
        this.active = true;
    }

    public UUID getEndpointsChangedBy() {
        return endpointsChangedBy;
    }

    public Instant getEndpointsChangedAt() {
        return endpointsChangedAt;
    }

    public void deactivate() {
        this.active = false;
    }

    public List<String> webhookFingerprints() {
        return webhookClientFingerprints == null ? List.of() : Arrays.asList(webhookClientFingerprints.split(","));
    }

    private String endpoint(String url, String code, String label) {
        Guard.require(url != null && !url.isBlank() && url.length() <= 500, code, label + " obrigatória");
        String trimmed = url.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, label + " inválida");
        }
        // produção: somente HTTPS (mTLS); o simulador aceita qualquer endereço, pois não faz chamadas de rede
        boolean https = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        Guard.require(provider == PixProviderType.SANDBOX || https, code, label + " deve usar HTTPS");
        Guard.require(uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null, code,
                label + " não pode conter credenciais, parâmetros ou fragmento");
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getName() {
        return name;
    }

    public PixProviderType getProvider() {
        return provider;
    }

    public String getPixKey() {
        return pixKey;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public String getCredentials() {
        return credentials;
    }

    public String getCertificate() {
        return certificate;
    }

    public String getCertificateSha256() {
        return certificateSha256;
    }

    public Instant getCertificateExpiresAt() {
        return certificateExpiresAt;
    }

    public Instant getCredentialsUpdatedAt() {
        return credentialsUpdatedAt;
    }

    public WebhookAuthMode getWebhookAuth() {
        return webhookAuth;
    }

    public Instant getWebhookRegisteredAt() {
        return webhookRegisteredAt;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public boolean isActive() {
        return active;
    }
}
