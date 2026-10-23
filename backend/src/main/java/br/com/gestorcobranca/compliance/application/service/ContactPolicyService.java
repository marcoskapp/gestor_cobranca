package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases;
import br.com.gestorcobranca.compliance.domain.model.ChannelRule;
import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import br.com.gestorcobranca.compliance.domain.model.ContactPolicy;
import br.com.gestorcobranca.compliance.domain.model.ContactWindow;
import br.com.gestorcobranca.compliance.domain.repository.ContactPolicyRepository;
import br.com.gestorcobranca.compliance.domain.service.ContactRules;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.compliance.ContactPolicyPublished;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ContactPolicyService implements ContactPolicyUseCases {

    /** Tolerância para "agora" informado pelo cliente com pequena diferença de relógio. */
    private static final Duration PAST_TOLERANCE = Duration.ofMinutes(1);

    private final ContactPolicyRepository policies;
    private final CreditorUseCases creditors;
    private final TenantAdministration tenants;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ContactPolicyService(ContactPolicyRepository policies, CreditorUseCases creditors, TenantAdministration tenants,
                         AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                         BusinessEventLogger businessEvents, ApplicationEventPublisher events, Clock clock) {
        this.policies = policies;
        this.creditors = creditors;
        this.tenants = tenants;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public PolicyView publish(PublishPolicyCommand command) {
        requireScope(command.creditorId());
        Instant now = Instant.now(clock);
        Instant effectiveFrom = command.effectiveFrom() == null ? now : command.effectiveFrom();
        Guard.require(!effectiveFrom.isBefore(now.minus(PAST_TOLERANCE)), "contact_policy.effective_from_past",
                "A vigência não pode começar no passado: o histórico de políticas é imutável");
        List<ChannelRule> rules = command.rules().stream().map(ContactPolicyService::rule).toList();
        int version = policies.maxVersion(command.creditorId()) + 1;
        ContactPolicy policy = policies.save(new ContactPolicy(command.creditorId(), version, effectiveFrom, command.notes(),
                rules));
        AuditEntry audit = AuditEntry.of("contact_policy.published", "contact_policy", policy.getId())
                .creditor(policy.getCreditorId())
                .created("version", version)
                .created("effectiveFrom", effectiveFrom);
        policy.getRules().forEach(r -> audit.created("rule." + r.getChannel().name(), describe(r)));
        auditTrail.record(audit);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.POLICY_PUBLISHED)
                .creditor(policy.getCreditorId())
                .reference("contact_policy", policy.getId())
                .attr("policyKind", "contact")
                .attr("version", version)
                .attr("channels", policy.getRules().size()));
        events.publishEvent(new ContactPolicyPublished(Ids.newId(), currentActor.tenantId(), now, policy.getId(),
                policy.getCreditorId(), version, effectiveFrom));
        return view(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public EffectivePolicyView effective(UUID creditorId) {
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
            creditors.get(creditorId);
        }
        Instant now = Instant.now(clock);
        Optional<ContactPolicy> creditorPolicy = creditorId == null ? Optional.empty() : policies.findEffective(creditorId, now);
        Optional<ContactPolicy> tenantPolicy = policies.findEffective(null, now);
        TenantView tenant = tenants.currentTenant();
        ContactWindow tenantWindow = ContactWindow.ofTenant(tenant.contactWindowStart(), tenant.contactWindowEnd(),
                tenant.contactOnSaturday(), tenant.contactOnSunday());
        List<EffectiveChannelRule> channels = Arrays.stream(ContactChannel.values()).map(channel -> {
            Optional<ChannelRule> rule = ContactRules.effectiveRule(creditorPolicy, tenantPolicy, channel);
            String ruleSource = rule.isEmpty() ? "NONE"
                    : creditorPolicy.flatMap(p -> p.ruleFor(channel)).isPresent() ? "CREDITOR" : "TENANT";
            ContactWindow window = ContactRules.effectiveWindow(rule, tenantWindow);
            boolean ownWindow = rule.flatMap(ChannelRule::window).isPresent();
            return new EffectiveChannelRule(channel.name(), ruleSource, ownWindow ? "RULE" : "TENANT_DEFAULT",
                    window.start(), window.end(), days(window.days()),
                    rule.map(ChannelRule::getMaxPerDay).orElse(null), rule.map(ChannelRule::getMaxPerWeek).orElse(null),
                    rule.map(ChannelRule::getMinIntervalMinutes).orElse(null),
                    rule.map(ChannelRule::isBlockOnHolidays).orElse(false));
        }).toList();
        return new EffectivePolicyView(creditorId, tenantPolicy.map(ContactPolicy::getPolicyVersion).orElse(null),
                creditorPolicy.map(ContactPolicy::getPolicyVersion).orElse(null), channels);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyView> history(UUID creditorId) {
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        return policies.findHistory(creditorId).stream().map(ContactPolicyService::view).toList();
    }

    private void requireScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("contact_policy");
        } else {
            accessScope.requireCreditor(creditorId);
            creditors.requireActive(creditorId);
        }
    }

    private static ChannelRule rule(ChannelRuleInput input) {
        ContactChannel channel = ComplianceEnums.parse(ContactChannel.class, input.channel(), "contact_policy.channel_invalid",
                "Canal");
        boolean anyWindowField = input.windowStart() != null || input.windowEnd() != null
                || input.allowedDays() != null && !input.allowedDays().isEmpty();
        ContactWindow window = null;
        if (anyWindowField) {
            Guard.require(input.windowStart() != null && input.windowEnd() != null && input.allowedDays() != null
                            && !input.allowedDays().isEmpty(), "contact_policy.window_incomplete",
                    "Informe início, fim e dias da janela, ou nenhum deles para herdar a da assessoria");
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            input.allowedDays().forEach(d -> days.add(ComplianceEnums.parse(DayOfWeek.class, d,
                    "contact_policy.day_invalid", "Dia da semana")));
            window = new ContactWindow(input.windowStart(), input.windowEnd(), days);
        }
        return new ChannelRule(channel, window, input.maxPerDay(), input.maxPerWeek(), input.minIntervalMinutes(),
                input.blockOnHolidays());
    }

    private static String describe(ChannelRule r) {
        String window = r.window().map(w -> w.start() + "-" + w.end() + " " + days(w.days())).orElse("janela da assessoria");
        return "%s; dia=%s; semana=%s; intervalo=%s min; feriados=%s".formatted(window, r.getMaxPerDay(), r.getMaxPerWeek(),
                r.getMinIntervalMinutes(), r.isBlockOnHolidays() ? "bloqueia" : "permite");
    }

    private static List<String> days(Set<DayOfWeek> days) {
        return EnumSet.copyOf(days).stream().map(Enum::name).toList();
    }

    static PolicyView view(ContactPolicy p) {
        return new PolicyView(p.getId(), p.getCreditorId(), p.getPolicyVersion(), p.getEffectiveFrom(), p.getNotes(),
                p.getRules().stream().map(r -> new ChannelRuleView(r.getChannel().name(), r.window().isEmpty(),
                        r.window().map(ContactWindow::start).orElse(null), r.window().map(ContactWindow::end).orElse(null),
                        r.window().map(w -> days(w.days())).orElse(null), r.getMaxPerDay(), r.getMaxPerWeek(),
                        r.getMinIntervalMinutes(), r.isBlockOnHolidays())).toList(),
                p.getCreatedAt(), p.getCreatedBy());
    }
}
