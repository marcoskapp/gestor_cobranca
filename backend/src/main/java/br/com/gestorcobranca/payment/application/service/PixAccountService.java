package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ProviderException;
import br.com.gestorcobranca.payment.application.port.out.WebhookRouteDirectory;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.model.PixProviderType;
import br.com.gestorcobranca.payment.domain.model.WebhookAuthMode;
import br.com.gestorcobranca.payment.domain.repository.PixAccountRepository;
import br.com.gestorcobranca.payment.domain.service.ClientCertificates;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Service
@Transactional
class PixAccountService implements PixAccountUseCases {

    static final String RESOURCE = "pix_account";
    private static final int SECRET_VALUE_MAX = 500;
    private static final int WEBHOOK_SECRET_MIN = 32;

    private final PixAccountRepository accounts;
    private final WebhookRouteDirectory routes;
    private final PixCredentialCodec codec;
    private final PixGateway pixGateway;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final PaymentProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    PixAccountService(PixAccountRepository accounts, WebhookRouteDirectory routes, PixCredentialCodec codec,
                      PixGateway pixGateway, CreditorUseCases creditors, AccessScope accessScope,
                      CurrentActor currentActor, AuditTrail auditTrail, PaymentProperties properties, Clock clock) {
        this.accounts = accounts;
        this.routes = routes;
        this.codec = codec;
        this.pixGateway = pixGateway;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AccountView create(CreateAccountCommand command) {
        accessScope.requirePermission(Permission.INTEGRATION_MANAGE);
        Guard.require(command != null, "pix_account.required", "Dados da conta obrigatórios");
        PixProviderType provider = PaymentEnums.parse(PixProviderType.class, command.provider(),
                "pix_account.provider_invalid", "Provedor");
        if (!pixGateway.available(provider)) {
            throw new BusinessRuleException("pix_account.provider_unavailable", provider == PixProviderType.SANDBOX
                    ? "O provedor simulado só está disponível em ambiente local ou de teste"
                    : "Provedor Pix indisponível");
        }
        WebhookAuthMode auth = PaymentEnums.parse(WebhookAuthMode.class, command.webhookAuth(),
                "pix_account.webhook_auth_invalid", "Autenticação do webhook");
        requireScope(command.creditorId());
        if (command.creditorId() != null) {
            creditors.get(command.creditorId());
        }
        Optional<PixAccount> current = command.creditorId() == null ? accounts.findActiveDefault()
                : accounts.findActiveForCreditor(command.creditorId());
        if (current.isPresent()) {
            throw new ConflictException("pix_account.already_active",
                    "Já existe conta Pix ativa para este credor/assessoria; desative-a antes");
        }
        PixAccount account = new PixAccount(command.creditorId(), command.name(), provider, command.pixKey(),
                command.apiBaseUrl(), command.tokenUrl(), auth, command.webhookFingerprints(), newRoutingKey());
        applyCredentials(account, command.credentials(), true);
        PixAccount saved = accounts.saveAndFlush(account);
        routes.register(saved.getRoutingKey(), currentActor.tenantId(), saved.getId(), provider.name());
        auditTrail.record(AuditEntry.of("pix_account.created", RESOURCE, saved.getId()).creditor(saved.getCreditorId())
                .created("provider", provider).created("name", saved.getName()).created("pixKey", saved.getPixKey())
                .created("apiBaseUrl", saved.getApiBaseUrl()).created("webhookAuth", auth)
                .meta("credentialKeys", String.join(",", codec.keys(saved.getCredentials())))
                .meta("certificateSha256", saved.getCertificateSha256())
                .meta("certificateExpiresAt", saved.getCertificateExpiresAt()));
        return view(saved);
    }

    @Override
    public AccountView update(UUID accountId, UpdateAccountCommand command) {
        Guard.require(command != null, "pix_account.required", "Dados da conta obrigatórios");
        PixAccount account = forCommand(accountId);
        String beforeName = account.getName();
        String beforeKey = account.getPixKey();
        String beforeUrl = account.getApiBaseUrl();
        String beforeToken = account.getTokenUrl();
        WebhookAuthMode beforeAuth = account.getWebhookAuth();
        List<String> beforeFingerprints = account.webhookFingerprints();
        WebhookAuthMode auth = PaymentEnums.parse(WebhookAuthMode.class, command.webhookAuth(),
                "pix_account.webhook_auth_invalid", "Autenticação do webhook");
        account.describe(command.name(), command.pixKey(), command.apiBaseUrl(), command.tokenUrl());
        account.configureWebhook(auth, command.webhookFingerprints());
        requireWebhookSecret(account, codec.decode(account.getCredentials()));
        accounts.save(account);
        auditTrail.record(AuditEntry.of("pix_account.updated", RESOURCE, account.getId()).creditor(account.getCreditorId())
                .change("name", beforeName, account.getName()).change("pixKey", beforeKey, account.getPixKey())
                .change("apiBaseUrl", beforeUrl, account.getApiBaseUrl()).change("tokenUrl", beforeToken, account.getTokenUrl())
                .change("webhookAuth", beforeAuth, account.getWebhookAuth())
                .change("webhookFingerprints", String.join(",", beforeFingerprints),
                        String.join(",", account.webhookFingerprints())));
        return view(account);
    }

    @Override
    public AccountView rotateCredentials(UUID accountId, CredentialsCommand command) {
        PixAccount account = forCommand(accountId);
        applyCredentials(account, command, false);
        accounts.save(account);
        auditTrail.record(AuditEntry.of("pix_account.credentials_rotated", RESOURCE, account.getId())
                .creditor(account.getCreditorId())
                .meta("credentialKeys", String.join(",", codec.keys(account.getCredentials())))
                .meta("certificateSha256", account.getCertificateSha256())
                .meta("certificateExpiresAt", account.getCertificateExpiresAt()));
        return view(account);
    }

    @Override
    public AccountView registerWebhook(UUID accountId) {
        PixAccount account = forCommand(accountId);
        Guard.requireState(account.isActive(), "pix_account.inactive", "Conta Pix inativa");
        String url = webhookUrl(account);
        try {
            pixGateway.provider(account).configureWebhook(pixGateway.connection(account), url);
        } catch (ProviderException e) {
            throw new BusinessRuleException("pix_account.webhook_registration_failed",
                    "O PSP recusou a configuração do webhook (%s)".formatted(e.code()));
        }
        account.webhookRegistered(Instant.now(clock));
        accounts.save(account);
        auditTrail.record(AuditEntry.of("pix_account.webhook_registered", RESOURCE, account.getId())
                .creditor(account.getCreditorId()).meta("webhookAuth", account.getWebhookAuth()));
        return view(account);
    }

    @Override
    public AccountView activate(UUID accountId) {
        PixAccount account = forCommand(accountId);
        if (account.isActive()) {
            return view(account);
        }
        Optional<PixAccount> current = account.getCreditorId() == null ? accounts.findActiveDefault()
                : accounts.findActiveForCreditor(account.getCreditorId());
        if (current.isPresent()) {
            throw new ConflictException("pix_account.already_active", "Já existe conta Pix ativa para este credor/assessoria");
        }
        account.activate();
        accounts.saveAndFlush(account);
        routes.setActive(account.getRoutingKey(), true);
        auditTrail.record(AuditEntry.of("pix_account.activated", RESOURCE, account.getId()).creditor(account.getCreditorId())
                .change("active", false, true));
        return view(account);
    }

    @Override
    public AccountView deactivate(UUID accountId) {
        PixAccount account = forCommand(accountId);
        if (!account.isActive()) {
            return view(account);
        }
        account.deactivate();
        accounts.save(account);
        routes.setActive(account.getRoutingKey(), false);
        auditTrail.record(AuditEntry.of("pix_account.deactivated", RESOURCE, account.getId())
                .creditor(account.getCreditorId()).change("active", true, false));
        return view(account);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView get(UUID accountId) {
        accessScope.requirePermission(Permission.INTEGRATION_MANAGE);
        return view(loadVisible(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        accessScope.requirePermission(Permission.INTEGRATION_MANAGE);
        return accounts.findAllByOrderByNameAsc().stream()
                .filter(a -> a.getCreditorId() == null ? accessScope.hasAllCreditors()
                        : accessScope.canAccessCreditor(a.getCreditorId()))
                .map(this::view).toList();
    }

    /**
     * Valida e grava os segredos. API do BACEN: OAuth2 ({@code clientId}/{@code clientSecret}) e
     * certificado mTLS obrigatórios na criação; HMAC exige segredo de 32+ caracteres. Na rotação, o
     * certificado só é trocado quando informado.
     */
    private void applyCredentials(PixAccount account, CredentialsCommand command, boolean creating) {
        Guard.require(command != null, "pix_account.credentials_required", "Credenciais obrigatórias");
        boolean bacen = account.getProvider() == PixProviderType.BACEN_API;
        Map<String, String> values = new TreeMap<>();
        put(values, PixCredentialCodec.CLIENT_ID, command.clientId());
        put(values, PixCredentialCodec.CLIENT_SECRET, command.clientSecret());
        put(values, PixCredentialCodec.WEBHOOK_SECRET, command.webhookSecret());
        Map<String, String> previous = codec.decode(account.getCredentials());
        boolean newCertificate = command.certificateBase64() != null && !command.certificateBase64().isBlank();
        if (bacen) {
            Guard.require(values.containsKey(PixCredentialCodec.CLIENT_ID) && values.containsKey(PixCredentialCodec.CLIENT_SECRET),
                    "pix_account.oauth_required", "Informe client_id e client_secret do PSP");
            Guard.require(newCertificate || (!creating && account.getCertificate() != null),
                    "pix_account.certificate_required", "A API Pix exige certificado de cliente (mTLS) em PKCS#12");
        }
        String certificate = null;
        ClientCertificates.Info info = null;
        if (newCertificate) {
            byte[] pkcs12;
            try {
                pkcs12 = Base64.getDecoder().decode(command.certificateBase64().replaceAll("\\s", ""));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("pix_account.certificate_invalid", "Certificado deve estar em Base64");
            }
            info = ClientCertificates.inspect(pkcs12, command.certificatePassword(), Instant.now(clock));
            certificate = Base64.getEncoder().encodeToString(pkcs12);
            put(values, PixCredentialCodec.CERTIFICATE_PASSWORD, command.certificatePassword());
        } else if (previous.containsKey(PixCredentialCodec.CERTIFICATE_PASSWORD)) {
            values.put(PixCredentialCodec.CERTIFICATE_PASSWORD, previous.get(PixCredentialCodec.CERTIFICATE_PASSWORD));
        }
        requireWebhookSecret(account, values);
        account.replaceCredentials(codec.encode(values), certificate, info == null ? null : info.sha256(),
                info == null ? null : info.notAfter(), Instant.now(clock));
    }

    private static void requireWebhookSecret(PixAccount account, Map<String, String> values) {
        if (account.getWebhookAuth() == WebhookAuthMode.HMAC) {
            String secret = values.get(PixCredentialCodec.WEBHOOK_SECRET);
            Guard.require(secret != null && secret.length() >= WEBHOOK_SECRET_MIN, "pix_account.webhook_secret_required",
                    "Webhook com HMAC exige segredo de ao menos 32 caracteres");
        }
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            Guard.require(value.length() <= SECRET_VALUE_MAX, "pix_account.credential_too_long",
                    "Valor de credencial muito longo");
            values.put(key, value.trim());
        }
    }

    String webhookUrl(PixAccount account) {
        String base = properties.webhook().publicBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + ApiPaths.WEBHOOKS + "/pix/"
                + account.getRoutingKey();
    }

    private PixAccount forCommand(UUID accountId) {
        accessScope.requirePermission(Permission.INTEGRATION_MANAGE);
        PixAccount account = loadVisible(accountId);
        requireScope(account.getCreditorId());
        return account;
    }

    private PixAccount loadVisible(UUID accountId) {
        PixAccount account = accounts.findById(accountId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, accountId));
        if (account.getCreditorId() == null) {
            if (!accessScope.hasAllCreditors()) {
                throw new ResourceNotFoundException(RESOURCE, accountId);
            }
        } else {
            accessScope.requireVisible(RESOURCE, accountId, account.getCreditorId());
        }
        return account;
    }

    private void requireScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors(RESOURCE);
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    private String newRoutingKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AccountView view(PixAccount a) {
        return new AccountView(a.getId(), a.getCreditorId(), a.getName(), a.getProvider().name(), a.getPixKey(),
                a.getApiBaseUrl(), a.getTokenUrl(), a.getWebhookAuth().name(), a.webhookFingerprints(), webhookUrl(a),
                List.copyOf(codec.keys(a.getCredentials())), a.getCertificateSha256(), a.getCertificateExpiresAt(),
                a.getCredentialsUpdatedAt(), a.getWebhookRegisteredAt(), a.isActive(),
                a.getVersion() == null ? 0 : a.getVersion());
    }
}
