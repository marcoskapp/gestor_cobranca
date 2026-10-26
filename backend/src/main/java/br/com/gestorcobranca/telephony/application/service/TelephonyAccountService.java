package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics.AccountCounters;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.Readiness;
import br.com.gestorcobranca.telephony.application.port.out.WebhookRouteDirectory;
import br.com.gestorcobranca.telephony.domain.model.AccountRoute;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.model.RouteScope;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AccountRouteRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class TelephonyAccountService implements TelephonyAccountUseCases {

    private static final int CREDENTIAL_VALUE_MAX = 1000;
    private static final int COMPARISON_MAX_DAYS = 366;

    private final TelephonyAccountRepository accounts;
    private final AccountRouteRepository routes;
    private final WebhookRouteDirectory webhookRoutes;
    private final TelephonyCredentialCodec credentialCodec;
    private final ProviderResolver resolver;
    private final CallStatistics statistics;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final TelephonyProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    TelephonyAccountService(TelephonyAccountRepository accounts, AccountRouteRepository routes,
                            WebhookRouteDirectory webhookRoutes, TelephonyCredentialCodec credentialCodec,
                            ProviderResolver resolver, CallStatistics statistics, CreditorUseCases creditors,
                            AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                            TelephonyProperties properties, Clock clock) {
        this.accounts = accounts;
        this.routes = routes;
        this.webhookRoutes = webhookRoutes;
        this.credentialCodec = credentialCodec;
        this.resolver = resolver;
        this.statistics = statistics;
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
        if (resolver.readiness(provider) == Readiness.NOT_IMPLEMENTED) {
            throw new BusinessRuleException("telephony_account.provider_unavailable",
                    "A integração com %s ainda não está disponível".formatted(provider.label()));
        }
        requireScope(command.creditorId());
        if (command.creditorId() != null) {
            creditors.get(command.creditorId());
        }
        String credentials = validatedCredentials(provider, command.credentials());
        boolean active = resolver.canDial(provider);
        TelephonyAccount account = new TelephonyAccount(provider, command.creditorId(), command.name(),
                command.callerId(), command.recordingEnabled(), command.recordingNotice(), credentials, newRoutingKey(),
                active, Instant.now(clock));
        account.changeRate(command.ratePerMinute());
        account = accounts.saveAndFlush(account);
        webhookRoutes.register(account.getRoutingKey(), currentActor.tenantId(), account.getId(), provider.name());
        if (!active) {
            webhookRoutes.setActive(account.getRoutingKey(), false);
        }
        auditTrail.record(AuditEntry.of("telephony_account.created", "telephony_account", account.getId())
                .creditor(account.getCreditorId())
                .created("provider", provider)
                .created("name", account.getName())
                .created("callerId", account.getCallerId())
                .created("recordingEnabled", account.isRecordingEnabled())
                .created("recordingNotice", account.isRecordingNotice())
                .created("ratePerMinute", account.getRatePerMinute())
                .created("active", account.isActive())
                .meta("credentialKeys", String.join(",", credentialCodec.keys(credentials))));
        if (active) {
            routeIfScopeEmpty(account);
        }
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
        BigDecimal beforeRate = account.getRatePerMinute();
        account.describe(command.name(), command.callerId(), command.recordingEnabled(), command.recordingNotice());
        account.changeRate(command.ratePerMinute());
        accounts.save(account);
        auditTrail.record(AuditEntry.of("telephony_account.updated", "telephony_account", account.getId())
                .creditor(account.getCreditorId())
                .change("name", beforeName, account.getName())
                .change("callerId", beforeCaller, account.getCallerId())
                .change("recordingEnabled", beforeRecording, account.isRecordingEnabled())
                .change("recordingNotice", beforeNotice, account.isRecordingNotice())
                .change("ratePerMinute", beforeRate, account.getRatePerMinute()));
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
        if (!resolver.canDial(account.getProvider())) {
            Readiness readiness = resolver.readiness(account.getProvider());
            throw new BusinessRuleException("telephony_account.provider_not_ready", readiness == Readiness.CONTRACT_PENDING
                    ? "A integração com %s aguarda a homologação com o fornecedor".formatted(account.getProvider().label())
                    : "A integração com %s ainda não está disponível".formatted(account.getProvider().label()));
        }
        account.activate();
        accounts.saveAndFlush(account);
        webhookRoutes.setActive(account.getRoutingKey(), true);
        auditTrail.record(AuditEntry.of("telephony_account.activated", "telephony_account", account.getId())
                .creditor(account.getCreditorId()).change("active", false, true));
        routeIfScopeEmpty(account);
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
        webhookRoutes.setActive(account.getRoutingKey(), false);
        auditTrail.record(AuditEntry.of("telephony_account.deactivated", "telephony_account", account.getId())
                .creditor(account.getCreditorId()).change("active", true, false)
                .meta("routes", routes.findUsingAccount(account.getId()).size()));
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
        return visibleAccounts().stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderView> providers() {
        return Arrays.stream(ProviderType.values())
                .filter(p -> p != ProviderType.SANDBOX || properties.sandbox().enabled())
                .map(p -> {
                    Readiness readiness = resolver.readiness(p);
                    return new ProviderView(p.name(), p.label(), new TreeSet<>(p.requiredCredentials()), readiness.name(),
                            resolver.canDial(p), readiness != Readiness.NOT_IMPLEMENTED);
                }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountComparison> comparison(LocalDate from, LocalDate to) {
        Guard.require(from != null && to != null && !to.isBefore(from), "telephony_comparison.period_invalid",
                "Período inválido");
        Guard.require(from.plusDays(COMPARISON_MAX_DAYS).isAfter(to), "telephony_comparison.period_too_long",
                "Período de até %d dias".formatted(COMPARISON_MAX_DAYS));
        Instant start = from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Map<UUID, AccountCounters> counters = statistics.accountCounters(start, end).stream()
                .collect(Collectors.toMap(AccountCounters::accountId, Function.identity()));
        return visibleAccounts().stream()
                .filter(a -> a.isActive() || counters.containsKey(a.getId()))
                .map(a -> comparison(a, counters.getOrDefault(a.getId(),
                        new AccountCounters(a.getId(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))))
                .toList();
    }

    private AccountComparison comparison(TelephonyAccount a, AccountCounters c) {
        BigDecimal minutes = BigDecimal.valueOf(c.billableSeconds()).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal cost = a.getRatePerMinute() == null ? null
                : minutes.multiply(a.getRatePerMinute()).setScale(2, RoundingMode.HALF_UP);
        return new AccountComparison(a.getId(), a.getProvider().name(), a.getName(), a.isActive(), c.dialed(),
                c.rejected(), c.answered(), c.connected(), c.failed(), c.fallbackIn(), ratio(c.answered(), c.dialed()),
                ratio(c.connected(), c.answered()), ratio(c.rejected() + c.failed(), c.dialed() + c.rejected()),
                ratio(c.ringSeconds(), c.ringEnded()), ratio(c.talkSeconds(), c.connected()), minutes,
                a.getRatePerMinute(), cost);
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    /** Primeira conta ativa de um escopo sem regra passa a ser a principal dele (padrão ou credor). */
    private void routeIfScopeEmpty(TelephonyAccount account) {
        RouteScope scope = account.getCreditorId() == null ? RouteScope.DEFAULT : RouteScope.CREDITOR;
        if (routes.findByScope(scope, account.getCreditorId()).isPresent()) {
            return;
        }
        AccountRoute route = routes.saveAndFlush(new AccountRoute(scope, account.getCreditorId(), account.getCreditorId(),
                account, null));
        auditTrail.record(AuditEntry.of("telephony_route.created", "telephony_route", route.getId())
                .creditor(route.getCreditorId())
                .created("scopeType", scope)
                .created("scopeId", route.getScopeId())
                .created("primaryAccountId", account.getId())
                .meta("automatic", true));
    }

    private List<TelephonyAccount> visibleAccounts() {
        return accounts.findAllByOrderByNameAsc().stream()
                .filter(a -> a.getCreditorId() == null ? accessScope.hasAllCreditors()
                        : accessScope.canAccessCreditor(a.getCreditorId()))
                .toList();
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
        resolver.adapter(provider).ifPresent(adapter -> adapter.validateCredentials(values));
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
                a.getCreatedAt(), a.getVersion() == null ? 0 : a.getVersion(), a.getProvider().label(),
                resolver.readiness(a.getProvider()).name(), resolver.canDial(a.getProvider()), a.getRatePerMinute());
    }
}
