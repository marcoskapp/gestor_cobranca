package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases;
import br.com.gestorcobranca.communication.application.port.out.WebhookRouteDirectory;
import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

@Service
@Transactional
class ChannelAccountService implements ChannelAccountUseCases {

    private static final int CREDENTIAL_VALUE_MAX = 1000;

    private final ChannelAccountRepository accounts;
    private final WebhookRouteDirectory routes;
    private final CredentialCodec credentialCodec;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final CommunicationProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    ChannelAccountService(ChannelAccountRepository accounts, WebhookRouteDirectory routes, CredentialCodec credentialCodec,
                          CreditorUseCases creditors, AccessScope accessScope, CurrentActor currentActor,
                          AuditTrail auditTrail, CommunicationProperties properties, Clock clock) {
        this.accounts = accounts;
        this.routes = routes;
        this.credentialCodec = credentialCodec;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AccountView create(CreateAccountCommand command) {
        Guard.require(command != null, "channel_account.required", "Dados da conta obrigatórios");
        ChannelType channel = CommunicationEnums.parse(ChannelType.class, command.channel(), "channel_account.channel_invalid",
                "Canal");
        ProviderType provider = CommunicationEnums.parse(ProviderType.class, command.provider(),
                "channel_account.provider_invalid", "Provedor");
        if (provider == ProviderType.SANDBOX && !properties.sandbox().enabled()) {
            throw new BusinessRuleException("channel_account.sandbox_disabled",
                    "O provedor simulado só está disponível em ambiente local ou de teste");
        }
        if (channel == ChannelType.SMS && provider != ProviderType.SANDBOX) {
            throw new BusinessRuleException("channel_account.sms_provider_unavailable",
                    "O provedor de SMS ainda não foi definido (issue #12)");
        }
        requireScope(command.creditorId());
        if (command.creditorId() != null) {
            creditors.get(command.creditorId());
        }
        Optional<ChannelAccount> current = command.creditorId() == null ? accounts.findActiveDefault(channel)
                : accounts.findActiveForCreditor(channel, command.creditorId());
        if (current.isPresent()) {
            throw new ConflictException("channel_account.already_active",
                    "Já existe conta ativa do canal %s para este credor; desative-a antes".formatted(channel));
        }
        String credentials = validatedCredentials(provider, command.credentials());
        Instant now = Instant.now(clock);
        ChannelAccount account = accounts.saveAndFlush(new ChannelAccount(channel, provider, command.creditorId(),
                command.name(), command.senderAddress(), command.senderName(), command.replyTo(), credentials,
                newRoutingKey(), now));
        if (provider.hasWebhook()) {
            routes.register(account.getRoutingKey(), currentActor.tenantId(), account.getId(), provider.name());
        }
        auditTrail.record(AuditEntry.of("channel_account.created", "channel_account", account.getId())
                .creditor(account.getCreditorId())
                .created("channel", channel)
                .created("provider", provider)
                .created("name", account.getName())
                .created("senderAddress", account.getSenderAddress())
                .meta("credentialKeys", String.join(",", credentialCodec.keys(credentials))));
        return view(account);
    }

    @Override
    public AccountView update(UUID accountId, UpdateAccountCommand command) {
        Guard.require(command != null, "channel_account.required", "Dados da conta obrigatórios");
        ChannelAccount account = forCommand(accountId);
        String beforeName = account.getName();
        String beforeSender = account.getSenderAddress();
        String beforeSenderName = account.getSenderName();
        String beforeReplyTo = account.getReplyTo();
        account.describe(command.name(), command.senderAddress(), command.senderName(), command.replyTo());
        accounts.save(account);
        auditTrail.record(AuditEntry.of("channel_account.updated", "channel_account", account.getId())
                .creditor(account.getCreditorId())
                .change("name", beforeName, account.getName())
                .change("senderAddress", beforeSender, account.getSenderAddress())
                .change("senderName", beforeSenderName, account.getSenderName())
                .change("replyTo", beforeReplyTo, account.getReplyTo()));
        return view(account);
    }

    @Override
    public AccountView rotateCredentials(UUID accountId, Map<String, String> credentials) {
        ChannelAccount account = forCommand(accountId);
        String encoded = validatedCredentials(account.getProvider(), credentials);
        account.replaceCredentials(encoded, Instant.now(clock));
        accounts.save(account);
        auditTrail.record(AuditEntry.of("channel_account.credentials_rotated", "channel_account", account.getId())
                .creditor(account.getCreditorId())
                .meta("credentialKeys", String.join(",", credentialCodec.keys(encoded))));
        return view(account);
    }

    @Override
    public AccountView activate(UUID accountId) {
        ChannelAccount account = forCommand(accountId);
        if (account.isActive()) {
            return view(account);
        }
        Optional<ChannelAccount> current = account.getCreditorId() == null ? accounts.findActiveDefault(account.getChannel())
                : accounts.findActiveForCreditor(account.getChannel(), account.getCreditorId());
        if (current.isPresent()) {
            throw new ConflictException("channel_account.already_active",
                    "Já existe conta ativa do canal %s para este credor".formatted(account.getChannel()));
        }
        account.activate();
        accounts.saveAndFlush(account);
        if (account.getProvider().hasWebhook()) {
            routes.setActive(account.getRoutingKey(), true);
        }
        auditTrail.record(AuditEntry.of("channel_account.activated", "channel_account", account.getId())
                .creditor(account.getCreditorId()).change("active", false, true));
        return view(account);
    }

    @Override
    public AccountView deactivate(UUID accountId) {
        ChannelAccount account = forCommand(accountId);
        if (!account.isActive()) {
            return view(account);
        }
        account.deactivate();
        accounts.save(account);
        if (account.getProvider().hasWebhook()) {
            routes.setActive(account.getRoutingKey(), false);
        }
        auditTrail.record(AuditEntry.of("channel_account.deactivated", "channel_account", account.getId())
                .creditor(account.getCreditorId()).change("active", true, false));
        return view(account);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView get(UUID accountId) {
        ChannelAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("channel_account", accountId));
        requireVisible(account);
        return view(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accounts.findAllByOrderByChannelAscNameAsc().stream()
                .filter(a -> a.getCreditorId() == null ? accessScope.hasAllCreditors()
                        : accessScope.canAccessCreditor(a.getCreditorId()))
                .map(this::view)
                .toList();
    }

    private ChannelAccount forCommand(UUID accountId) {
        ChannelAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("channel_account", accountId));
        requireVisible(account);
        requireScope(account.getCreditorId());
        return account;
    }

    private void requireVisible(ChannelAccount account) {
        if (account.getCreditorId() == null) {
            if (!accessScope.hasAllCreditors()) {
                throw ResourceNotFoundException.of("channel_account", account.getId());
            }
        } else {
            accessScope.requireVisible("channel_account", account.getId(), account.getCreditorId());
        }
    }

    private void requireScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("channel_account");
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    private String validatedCredentials(ProviderType provider, Map<String, String> credentials) {
        Map<String, String> values = credentials == null ? Map.of() : credentials;
        for (String key : values.keySet()) {
            Guard.require(provider.requiredCredentials().contains(key), "channel_account.credential_unknown",
                    "Credencial não prevista para %s: %s".formatted(provider, key));
        }
        for (String key : provider.requiredCredentials()) {
            String value = values.get(key);
            Guard.require(value != null && !value.isBlank(), "channel_account.credential_required",
                    "Credencial obrigatória para %s: %s".formatted(provider, key));
            Guard.require(value.length() <= CREDENTIAL_VALUE_MAX, "channel_account.credential_too_long",
                    "Credencial %s muito longa".formatted(key));
        }
        return values.isEmpty() ? null : credentialCodec.encode(values);
    }

    private String newRoutingKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AccountView view(ChannelAccount a) {
        String webhookPath = a.getProvider() == ProviderType.WHATSAPP_CLOUD
                ? ApiPaths.WEBHOOKS + "/" + a.getChannel().name().toLowerCase(Locale.ROOT) + "/" + a.getRoutingKey()
                : null;
        return new AccountView(a.getId(), a.getChannel().name(), a.getProvider().name(), a.getCreditorId(), a.getName(),
                a.getSenderAddress(), a.getSenderName(), a.getReplyTo(), new TreeSet<>(credentialCodec.keys(a.getCredentials())),
                a.getCredentialsUpdatedAt(), webhookPath, a.isActive(), a.getCreatedAt(),
                a.getVersion() == null ? 0 : a.getVersion());
    }
}
