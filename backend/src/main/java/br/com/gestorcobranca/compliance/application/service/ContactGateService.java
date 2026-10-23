package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.out.DebtorContactLock;
import br.com.gestorcobranca.compliance.domain.model.AttemptOrigin;
import br.com.gestorcobranca.compliance.domain.model.ChannelRule;
import br.com.gestorcobranca.compliance.domain.model.ContactAttempt;
import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import br.com.gestorcobranca.compliance.domain.model.ContactPolicy;
import br.com.gestorcobranca.compliance.domain.model.ContactWindow;
import br.com.gestorcobranca.compliance.domain.model.Holiday;
import br.com.gestorcobranca.compliance.domain.repository.ContactAttemptRepository;
import br.com.gestorcobranca.compliance.domain.repository.ContactPolicyRepository;
import br.com.gestorcobranca.compliance.domain.repository.ContactRestrictionRepository;
import br.com.gestorcobranca.compliance.domain.repository.HolidayRepository;
import br.com.gestorcobranca.compliance.domain.service.ContactRules;
import br.com.gestorcobranca.compliance.domain.service.ContactRules.Decision;
import br.com.gestorcobranca.compliance.domain.service.ContactRules.Situation;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementação do porteiro de contato. Reúne os dados (devedor, credor, restrições, política
 * vigente, janela da assessoria, feriados, tentativas) e delega a decisão a {@link ContactRules}.
 * <p>
 * Concorrência em {@link #authorizeAndRecord}: o lock por (assessoria, devedor) é obtido
 * <em>antes</em> de ler as tentativas; quem chega depois espera o commit de quem chegou antes e
 * enxerga a tentativa já gravada (READ COMMITTED lê o estado confirmado a cada comando).
 */
@Service
@Transactional
class ContactGateService implements ContactGate {

    /** Folga aceita entre o instante informado e o relógio para registrar uma tentativa. */
    private static final Duration RECORD_TOLERANCE = Duration.ofMinutes(5);
    /** Horizonte de feriados carregados para calcular o próximo instante permitido. */
    private static final int HOLIDAY_HORIZON_DAYS = 60;

    private final ContactRestrictionRepository restrictions;
    private final ContactPolicyRepository policies;
    private final HolidayRepository holidays;
    private final ContactAttemptRepository attempts;
    private final DebtorContactLock lock;
    private final DebtorUseCases debtors;
    private final DebtorContactUseCases debtorContacts;
    private final CreditorUseCases creditors;
    private final TenantAdministration tenants;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final BusinessEventLogger businessEvents;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ContactRules rules = new ContactRules(TimeConfig.BUSINESS_ZONE);

    ContactGateService(ContactRestrictionRepository restrictions, ContactPolicyRepository policies,
                       HolidayRepository holidays, ContactAttemptRepository attempts, DebtorContactLock lock,
                       DebtorUseCases debtors, DebtorContactUseCases debtorContacts, CreditorUseCases creditors,
                       TenantAdministration tenants, AccessScope accessScope, CurrentActor currentActor,
                       BusinessEventLogger businessEvents, MeterRegistry meterRegistry, Clock clock) {
        this.restrictions = restrictions;
        this.policies = policies;
        this.holidays = holidays;
        this.attempts = attempts;
        this.lock = lock;
        this.debtors = debtors;
        this.debtorContacts = debtorContacts;
        this.creditors = creditors;
        this.tenants = tenants;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.businessEvents = businessEvents;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ContactDecision evaluate(ContactRequest request) {
        Prepared prepared = prepare(request);
        Decision decision = decide(prepared);
        count("evaluate", decision);
        return toView(decision, null);
    }

    @Override
    public ContactDecision authorizeAndRecord(ContactRequest request) {
        Prepared prepared = prepare(request);
        Instant now = Instant.now(clock);
        Guard.require(Duration.between(prepared.at(), now).abs().compareTo(RECORD_TOLERANCE) <= 0,
                "contact_gate.attempt_time_invalid", "A tentativa deve ser registrada no momento do contato");
        lock.lock(currentActor.tenantId(), prepared.debtorId());
        Decision decision = decide(prepared);
        count("authorize", decision);
        if (!decision.allowed()) {
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.CONTACT_DENIED)
                    .creditor(prepared.creditorId()).debtor(prepared.debtorId())
                    .channel(prepared.channel().name())
                    .attr("origin", prepared.origin())
                    .attr("reasons", decision.reasons().stream().map(Enum::name).collect(Collectors.joining(","))));
            return toView(decision, null);
        }
        ContactAttempt attempt = attempts.save(new ContactAttempt(prepared.debtorId(), prepared.creditorId(),
                prepared.channel(), prepared.at(), prepared.origin(), request.referenceType(), request.referenceId()));
        return toView(decision, attempt.getId());
    }

    /** Validação do pedido e escopo: devedor visível, credor existente e no escopo do ator. */
    private Prepared prepare(ContactRequest request) {
        Guard.require(request != null && request.debtorId() != null, "contact_gate.debtor_required", "Devedor obrigatório");
        Guard.require(request.creditorId() != null, "contact_gate.creditor_required", "Credor obrigatório");
        ContactChannel channel = ComplianceEnums.parse(ContactChannel.class, request.channel(),
                "contact_gate.channel_invalid", "Canal");
        AttemptOrigin origin = ComplianceEnums.parse(AttemptOrigin.class, request.origin(),
                "contact_gate.origin_invalid", "Origem");
        accessScope.requireCreditor(request.creditorId());
        creditors.get(request.creditorId());
        boolean anonymized = "ANONYMIZED".equals(debtors.get(request.debtorId()).status());
        String contactHash = null;
        if (request.contactValue() != null && !request.contactValue().isBlank()) {
            String type = channel.contactValueType().map(Enum::name).orElseThrow(() -> new BusinessRuleException(
                    "contact_gate.contact_not_applicable", "O canal %s não usa telefone nem e-mail".formatted(channel)));
            contactHash = debtorContacts.fingerprint(type, request.contactValue()).hash();
        }
        Instant at = request.at() == null ? Instant.now(clock) : request.at();
        return new Prepared(request.debtorId(), request.creditorId(), channel, origin, contactHash, at, anonymized);
    }

    private Decision decide(Prepared p) {
        Optional<ContactPolicy> creditorPolicy = policies.findEffective(p.creditorId(), p.at());
        Optional<ContactPolicy> tenantPolicy = policies.findEffective(null, p.at());
        Optional<ChannelRule> rule = ContactRules.effectiveRule(creditorPolicy, tenantPolicy, p.channel());
        ContactWindow window = ContactRules.effectiveWindow(rule, tenantWindow());

        LocalDate day = LocalDate.ofInstant(p.at(), TimeConfig.BUSINESS_ZONE);
        Set<LocalDate> holidayDates = holidays.findByDateBetweenOrderByDateAsc(day.minusDays(1), day.plusDays(HOLIDAY_HORIZON_DAYS))
                .stream().map(Holiday::getDate).collect(Collectors.toSet());

        Instant since = rules.startOfWeek(p.at());
        Optional<Duration> interval = rule.flatMap(ChannelRule::minInterval);
        if (interval.isPresent() && p.at().minus(interval.get()).isBefore(since)) {
            since = p.at().minus(interval.get());
        }
        List<Instant> previous = attempts.findAttemptTimes(p.debtorId(), p.channel(), since);

        return rules.evaluate(new Situation(p.debtorId(), p.creditorId(), p.channel(), p.contactHash(), p.at(),
                p.anonymized(), restrictions.findActiveForGate(p.debtorId(), p.contactHash()), rule.orElse(null),
                window, holidayDates, previous));
    }

    private ContactWindow tenantWindow() {
        TenantView tenant = tenants.currentTenant();
        return ContactWindow.ofTenant(tenant.contactWindowStart(), tenant.contactWindowEnd(), tenant.contactOnSaturday(),
                tenant.contactOnSunday());
    }

    private void count(String operation, Decision decision) {
        meterRegistry.counter("compliance.contact_gate.decisions", "operation", operation,
                "outcome", decision.allowed() ? "allowed" : "denied").increment();
        decision.reasons().forEach(r -> meterRegistry.counter("compliance.contact_gate.denials",
                "operation", operation, "reason", r.name()).increment());
    }

    private static ContactDecision toView(Decision decision, UUID attemptId) {
        return new ContactDecision(decision.allowed(),
                decision.reasons().stream().map(r -> DenialReason.valueOf(r.name())).toList(),
                decision.nextAllowedAt(), attemptId);
    }

    private record Prepared(UUID debtorId, UUID creditorId, ContactChannel channel, AttemptOrigin origin,
                            String contactHash, Instant at, boolean anonymized) {
    }
}
