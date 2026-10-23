package br.com.gestorcobranca.telephony.application.service;

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
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases;
import br.com.gestorcobranca.telephony.application.port.out.WebhookRouteDirectory;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

@Service
@Transactional
class TelephonyAccountService implements TelephonyAccountUseCases {

    private static final int CREDENTIAL_VALUE_MAX = 1000;

    private final TelephonyAccountRepository accounts;
    private final WebhookRouteDirectory routes;
    private final CredentialCodec credentialCodec;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final TelephonyProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    TelephonyAccountService(TelephonyAccountRepository accounts, WebhookRouteDirectory routes,
                            CredentialCodec credentialCodec, CreditorUseCases creditors, AccessScope accessScope,
                            CurrentActor currentActor, AuditTrail auditTrail, TelephonyProperties properties,
                            Clock clock) {
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
        Guard.require(command != null, "telephony_account.required", "Dados da conta obrigatórios");
        ProviderType provider = TelephonyEnums.parse(ProviderType.class, command.provider(),
                "telephony_account.provider_invalid", "Provedor");
        if (provider == ProviderType.SANDBOX && !properties.sandbox().enabled()) {
            throw new BusinessRuleException("telephony_account.sandbox_disabled",
                    "O provedor simulado só está disponível em ambiente local ou de teste");
        }
        requireScope(command.creditorId());
        if (command.creditorId() != null) {
            creditors.get(command.creditorId());
        }
        Optional<TelephonyAccount> current = command.creditorId() == null ? accounts.findActiveDefault()
                : accounts.findActiveForCreditor(command.creditorId());
        if (current.isPresent()) {
            throw new ConflictException("telephony_account.already_active",
                    "Já existe conta de telefonia ativa para este credor; desative-a antes");
        }
        String credentials = validatedCredentials(provider, command.credentials());
        TelephonyAccount account = accounts.saveAndFlush(new TelephonyAccount(provider, command.creditorId(),
                command.name(), command.callerId(), command.recordingEnabled(), command.recordingNotice(), credentials,
                newRoutingKey(), Instant.now(clock)));
        routes.register(account.getRoutingKey(), currentActor.tenantId(), account.getId(), provider.name());
        auditTrail.record(AuditEntry.of("telephony_account.created", "telephony_account", account.getId())
                .creditor(account.getCreditorId())
                .created("provider", provider)
                .created("name", account.getName())
                .created("callerId", account.getCallerId())
                .created("recordingEnabled", account.isRecordingEnabled())
                .created("recordingNotice", account.isRecordingNotice())
                .meta("credentialKeys", String.join(",", credentialCodec.keys(credentials))));
        return view(account);
    }

    @Override
    public AccountView update(UUID accountId, UpdateAccountCommand command) {
        Guard.require(command != null, "telephony_account.required", "Dados da conta obrigatórios");
        TelephonyAccount account = forCommand(accountId);
        String beforeName = account.getName();
        String beforeCaller = account.getCallerId();
        boolean beforeRecording = account.isRecordingEnabled();
        boolean beforeNotice = account.isRecordingNotice();
        account.describe(command.name(), command.callerId(), command.recordingEnabled(), command.recordingNotice());
        accounts.save(account);
        auditTrail.record(AuditEntry.of("telephony_account.updated", "telephony_account", account.getId())
                .creditor(account.getCreditorId())
                .change("name", beforeName, account.getName())
                .change("callerId", beforeCaller, account.getCallerId())
                .change("recordingEnabled", beforeRecording, account.isRecordingEnabled())
                .change("recordingNotice", beforeNotice, account.isRecordingNotice()));
        return view(account);
    }

    @Override
    public AccountView rotateCredentials(UUID accountId, Map<String, String> credentials) {
        TelephonyAccount account = forCommand(accountId);
        String encoded = validatedCredentials(account.getProvider(), credentials);
        account.replaceCredentials(encoded, Instant.now(clock));
        accounts.save(account);
        auditTrail.record(AuditEntry.of("telephony_account.credentials_rotated", "telephony_account", account.getId())
                .creditor(account.getCreditorId())
                .meta("credentialKeys", String.join(",", credentialCodec.keys(encoded))));
        return view(account);
    }

    @Override
    public AccountView activate(UUID accountId) {
        TelephonyAccount account = forCommand(accountId);
        if (account.isActive()) {
            return view(account);
        }
        Optional<TelephonyAccount> current = account.getCreditorId() == null ? accounts.findActiveDefault()
                : accounts.findActiveForCreditor(account.getCreditorId());
        if (current.isPresent()) {
            throw new ConflictException("telephony_account.already_active",
                    "Já existe conta de telefonia ativa para este credor");
        }
        account.activate();
        accounts.saveAndFlush(account);
        routes.setActive(account.getRoutingKey(), true);
        auditTrail.record(AuditEntry.of("telephony_account.activated", "telephony_account", account.getId())
                .creditor(account.getCreditorId()).change("active", false, true));
        return view(account);
    }

    @Override
    public AccountView deactivate(UUID accountId) {
        TelephonyAccount account = forCommand(accountId);
        if (!account.isActive()) {
            return view(account);
        }
        account.deactivate();
        accounts.save(account);
        routes.setActive(account.getRoutingKey(), false);
        auditTrail.record(AuditEntry.of("telephony_account.deactivated", "telephony_account", account.getId())
                .creditor(account.getCreditorId()).change("active", true, false));
        return view(account);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView get(UUID accountId) {
        TelephonyAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_account", accountId));
        requireVisible(account);
        return view(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accounts.findAllByOrderByNameAsc().stream()
                .filter(a -> a.getCreditorId() == null ? accessScope.hasAllCreditors()
                        : accessScope.canAccessCreditor(a.getCreditorId()))
                .map(this::view).toList();
    }

    private TelephonyAccount forCommand(UUID accountId) {
        TelephonyAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_account", accountId));
        requireVisible(account);
        requireScope(account.getCreditorId());
        return account;
    }

    private void requireVisible(TelephonyAccount account) {
        if (account.getCreditorId() == null) {
            if (!accessScope.hasAllCreditors()) {
                throw ResourceNotFoundException.of("telephony_account", account.getId());
            }
        } else {
            accessScope.requireVisible("telephony_account", account.getId(), account.getCreditorId());
        }
    }

    private void requireScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("telephony_account");
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    private String validatedCredentials(ProviderType provider, Map<String, String> credentials) {
        Map<String, String> values = credentials == null ? Map.of() : credentials;
        for (String key : values.keySet()) {
            Guard.require(provider.requiredCredentials().contains(key), "telephony_account.credential_unknown",
                    "Credencial não prevista para %s: %s".formatted(provider, key));
        }
        for (String key : provider.requiredCredentials()) {
            String value = values.get(key);
            Guard.require(value != null && !value.isBlank(), "telephony_account.credential_required",
                    "Credencial obrigatória para %s: %s".formatted(provider, key));
            Guard.require(value.length() <= CREDENTIAL_VALUE_MAX, "telephony_account.credential_too_long",
                    "Credencial %s muito longa".formatted(key));
        }
        return values.isEmpty() ? null : credentialCodec.encode(values);
    }

    private String newRoutingKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AccountView view(TelephonyAccount a) {
        return new AccountView(a.getId(), a.getProvider().name(), a.getCreditorId(), a.getName(), a.getCallerId(),
                a.isRecordingEnabled(), a.isRecordingNotice(), new TreeSet<>(credentialCodec.keys(a.getCredentials())),
                a.getCredentialsUpdatedAt(), ApiPaths.WEBHOOKS + "/telephony/" + a.getRoutingKey(), a.isActive(),
                a.getCreatedAt(), a.getVersion() == null ? 0 : a.getVersion());
    }
}
