package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases;
import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.PolicyStatus;
import br.com.gestorcobranca.assistant.domain.repository.AssistantPolicyRepository;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class AssistantPolicyService implements AssistantPolicyUseCases {

    static final String RESOURCE = "assistant_policy";

    private final AssistantPolicyRepository policies;
    private final AssistantCodec codec;
    private final PromptCatalog prompts;
    private final PolicyLookup lookup;
    private final AuthorityUseCases authority;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final JobQueue jobQueue;
    private final AssistantMetrics metrics;
    private final Clock clock;

    AssistantPolicyService(AssistantPolicyRepository policies, AssistantCodec codec, PromptCatalog prompts,
                           PolicyLookup lookup, AuthorityUseCases authority, PortfolioUseCases portfolios,
                           AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                           BusinessEventLogger businessEvents, JobQueue jobQueue, AssistantMetrics metrics, Clock clock) {
        this.policies = policies;
        this.codec = codec;
        this.prompts = prompts;
        this.lookup = lookup;
        this.authority = authority;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyView> list() {
        return policies.findAllByOrderByCodeAscPolicyVersionDesc().stream().filter(this::visible).map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyView get(UUID policyId) {
        return view(load(policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyView> versions(String code) {
        List<AssistantPolicy> all = policies.findByCodeOrderByPolicyVersionDesc(normalizeCode(code));
        if (all.isEmpty() || !visible(all.getFirst())) {
            throw new ResourceNotFoundException(RESOURCE, code);
        }
        return all.stream().map(this::view).toList();
    }

    @Override
    public PolicyView createDraft(CreatePolicyCommand command) {
        Guard.require(command != null && command.settings() != null, "assistant_policy.settings_required",
                "Parâmetros da política obrigatórios");
        UUID creditorId = scope(command.creditorId(), command.portfolioId());
        String code = normalizeCode(command.code());
        List<AssistantPolicy> timeline = policies.findByCodeOrderByPolicyVersionDesc(code);
        if (!timeline.isEmpty()) {
            throw new ConflictException("assistant_policy.code_taken",
                    "Código já usado: crie uma nova versão a partir da existente");
        }
        boolean scopeTaken = policies.findAllByOrderByCodeAscPolicyVersionDesc().stream()
                .anyMatch(p -> p.sameScope(creditorId, command.portfolioId()));
        if (scopeTaken) {
            throw new ConflictException("assistant_policy.scope_taken",
                    "Já existe política para este escopo: crie uma nova versão dela");
        }
        PolicySettings settings = validated(command.settings());
        AssistantPolicy policy = policies.save(new AssistantPolicy(creditorId, command.portfolioId(), code,
                command.name(), settings, codec.settings(settings)));
        auditTrail.record(AuditEntry.of("assistant_policy.created", RESOURCE, policy.getId()).creditor(creditorId)
                .created("code", code).created("mode", settings.mode()).meta("portfolioId", command.portfolioId()));
        return view(policy);
    }

    @Override
    public PolicyView updateDraft(UUID policyId, UpdatePolicyCommand command) {
        Guard.require(command != null && command.settings() != null, "assistant_policy.settings_required",
                "Parâmetros da política obrigatórios");
        AssistantPolicy policy = forCommand(policyId);
        PolicySettings before = codec.settings(policy.getSettings());
        PolicySettings settings = validated(command.settings());
        policy.update(command.name(), settings, codec.settings(settings));
        policies.save(policy);
        auditTrail.record(AuditEntry.of("assistant_policy.updated", RESOURCE, policy.getId())
                .creditor(policy.getCreditorId())
                .change("mode", before.mode(), settings.mode())
                .change("authorityProfileId", before.authorityProfileId(), settings.authorityProfileId())
                .change("prompt", before.promptCode() + "@" + before.promptVersion(),
                        settings.promptCode() + "@" + settings.promptVersion())
                .change("settings", codec.toMap(codec.settings(before)), codec.toMap(codec.settings(settings))));
        return view(policy);
    }

    @Override
    public PolicyView newVersion(UUID policyId) {
        AssistantPolicy source = forCommand(policyId);
        List<AssistantPolicy> timeline = policies.findByCodeOrderByPolicyVersionDesc(source.getCode());
        Guard.requireState(timeline.stream().noneMatch(p -> p.getStatus() == PolicyStatus.DRAFT),
                "assistant_policy.draft_exists", "Já existe rascunho para esta política");
        PolicySettings settings = codec.settings(source.getSettings()).normalized();
        AssistantPolicy draft = policies.save(new AssistantPolicy(source.getCreditorId(), source.getPortfolioId(),
                source.getCode(), source.getName(), settings, codec.settings(settings)));
        auditTrail.record(AuditEntry.of("assistant_policy.created", RESOURCE, draft.getId())
                .creditor(draft.getCreditorId()).created("code", draft.getCode()).meta("fromVersion",
                        source.getPolicyVersion()));
        return view(draft);
    }

    @Override
    public PolicyView publish(UUID policyId) {
        AssistantPolicy policy = forCommand(policyId);
        Guard.requireState(policy.getStatus() == PolicyStatus.DRAFT, "assistant_policy.not_draft",
                "Somente rascunho pode ser publicado");
        PolicySettings settings = validated(codec.settings(policy.getSettings()));
        return view(publishNow(policy, settings, "Substituída pela nova versão"));
    }

    @Override
    public PolicyView retire(UUID policyId, String reason) {
        AssistantPolicy policy = forCommand(policyId);
        policy.retire(Instant.now(clock), reason);
        policies.save(policy);
        auditTrail.record(AuditEntry.of("assistant_policy.retired", RESOURCE, policy.getId())
                .creditor(policy.getCreditorId()).reason(reason)
                .change("status", PolicyStatus.PUBLISHED, PolicyStatus.RETIRED));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.ASSISTANT_POLICY_RETIRED).creditor(policy.getCreditorId())
                .portfolio(policy.getPortfolioId()).reference(RESOURCE, policy.getId())
                .attr("code", policy.getCode()).attr("version", policy.getPolicyVersion()).attr("mode", policy.getMode()));
        metrics.policy(PolicyStatus.RETIRED.name());
        return view(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyView> effective(UUID creditorId, UUID portfolioId) {
        Guard.require(creditorId != null, "assistant_policy.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return lookup.effective(creditorId, portfolioId).map(e -> view(e.policy()));
    }

    @Override
    public PolicyView disablePortfolio(UUID portfolioId, String reason) {
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 300, "assistant_policy.reason_required",
                "Motivo obrigatório (até 300 caracteres)");
        PortfolioUseCases.PortfolioView portfolio = portfolios.get(portfolioId);
        accessScope.requireCreditor(portfolio.creditorId());
        Optional<AssistantPolicy> current = lookup.effective(portfolio.creditorId(), portfolioId).map(PolicyLookup.Effective::policy);
        PolicySettings base = current.map(p -> codec.settings(p.getSettings()).normalized())
                .orElse(PolicySettings.defaults(AssistantMode.OFF));
        PolicySettings off = withMode(base, AssistantMode.OFF);
        List<AssistantPolicy> scoped = policies.findAllByOrderByCodeAscPolicyVersionDesc().stream()
                .filter(p -> p.sameScope(portfolio.creditorId(), portfolioId)).toList();
        String code = scoped.isEmpty() ? "carteira-" + portfolioId.toString().substring(0, 8) : scoped.getFirst().getCode();
        AssistantPolicy policy = new AssistantPolicy(portfolio.creditorId(), portfolioId, code,
                "Desligamento da carteira " + portfolio.code(), off, codec.settings(off));
        AssistantPolicy published = publishNow(policy, off, "Desligamento imediato da carteira: " + reason.trim());
        jobQueue.enqueue(PortfolioDisableJob.TYPE, new PortfolioDisableJob.Payload(portfolioId, reason.trim()), null,
                "portfolio:" + portfolioId);
        auditTrail.record(AuditEntry.of("assistant.portfolio_disabled", RESOURCE, published.getId())
                .creditor(portfolio.creditorId()).reason(reason).meta("portfolioId", portfolioId));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.ASSISTANT_PORTFOLIO_DISABLED)
                .creditor(portfolio.creditorId()).portfolio(portfolioId).reference(RESOURCE, published.getId())
                .attr("previousMode", current.map(AssistantPolicy::getMode).orElse(null))
                .attr("previousPolicyId", current.map(p -> p.getId().toString()).orElse(null)));
        return view(published);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicySettings template(String mode) {
        AssistantMode m = mode == null || mode.isBlank() ? AssistantMode.SHADOW : parseMode(mode);
        return PolicySettings.defaults(m);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromptView> prompts() {
        return prompts.all().stream().map(p -> new PromptView(p.code(), p.version(), p.description(), p.sha256(),
                p.text().length())).toList();
    }

    // ------------------------------------------------------------------ publicação

    private AssistantPolicy publishNow(AssistantPolicy policy, PolicySettings settings, String supersedeReason) {
        Instant now = Instant.now(clock);
        prompts.require(settings.promptCode(), settings.promptVersion());
        List<AssistantPolicy> timeline = policies.findByCodeOrderByPolicyVersionDesc(policy.getCode());
        int next = timeline.stream().mapToInt(AssistantPolicy::getPolicyVersion).max().orElse(0) + 1;
        AssistantPolicy previous = timeline.stream().filter(p -> p.getStatus() == PolicyStatus.PUBLISHED)
                .findFirst().orElse(null);
        if (previous != null && !previous.getId().equals(policy.getId())) {
            previous.retire(now, supersedeReason.length() > 300 ? supersedeReason.substring(0, 300) : supersedeReason);
            policies.saveAndFlush(previous);
            metrics.policy(PolicyStatus.RETIRED.name());
        }
        policy.publish(now, currentActor.actorId(), next);
        AssistantPolicy saved = policies.saveAndFlush(policy);
        if (settings.authorityProfileId() != null) {
            // alçada do ator ASSISTANT desta versão (o id da versão é o sujeito no negotiation)
            authority.assign(new AuthorityUseCases.AssignCommand("ASSISTANT_POLICY", saved.getId(),
                    saved.getCreditorId(), settings.authorityProfileId()));
        }
        auditTrail.record(AuditEntry.of("assistant_policy.published", RESOURCE, saved.getId())
                .creditor(saved.getCreditorId())
                .change("status", PolicyStatus.DRAFT, PolicyStatus.PUBLISHED)
                .meta("code", saved.getCode()).meta("version", next).meta("mode", settings.mode())
                .meta("prompt", settings.promptCode() + "@" + settings.promptVersion())
                .meta("authorityProfileId", settings.authorityProfileId())
                .meta("portfolioId", saved.getPortfolioId())
                .meta("previousPolicyId", previous == null ? null : previous.getId()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.POLICY_PUBLISHED).creditor(saved.getCreditorId())
                .portfolio(saved.getPortfolioId()).reference(RESOURCE, saved.getId())
                .attr("policyKind", "assistant").attr("code", saved.getCode()).attr("version", next)
                .attr("mode", settings.mode()).attr("channels", String.join(",", settings.channels()))
                .attr("promptCode", settings.promptCode()).attr("promptVersion", settings.promptVersion())
                .attr("customAuthority", settings.authorityProfileId() != null)
                .attr("maxTurns", settings.maxTurns()).attr("reviewSampleRate", settings.reviewSampleRate())
                .attr("extraTriggers", settings.extraTriggers().size()));
        metrics.policy(PolicyStatus.PUBLISHED.name());
        return saved;
    }

    // ------------------------------------------------------------------ apoio

    private PolicySettings validated(PolicySettings settings) {
        PolicySettings normalized = settings.normalized();
        prompts.require(normalized.promptCode(), normalized.promptVersion());
        return normalized;
    }

    /** Valida o escopo pedido e devolve o credor da política. */
    private UUID scope(UUID creditorId, UUID portfolioId) {
        if (portfolioId != null) {
            PortfolioUseCases.PortfolioView portfolio = portfolios.get(portfolioId);
            Guard.require(creditorId == null || creditorId.equals(portfolio.creditorId()),
                    "assistant_policy.portfolio_creditor_mismatch", "A carteira é de outro credor");
            accessScope.requireCreditor(portfolio.creditorId());
            return portfolio.creditorId();
        }
        if (creditorId != null) {
            accessScope.requireCreditor(creditorId);
            return creditorId;
        }
        accessScope.requireAllCreditors(RESOURCE);
        return null;
    }

    private AssistantPolicy load(UUID policyId) {
        AssistantPolicy policy = policies.findById(policyId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, policyId));
        if (!visible(policy)) {
            throw ResourceNotFoundException.of(RESOURCE, policyId);
        }
        return policy;
    }

    private AssistantPolicy forCommand(UUID policyId) {
        AssistantPolicy policy = load(policyId);
        if (policy.getCreditorId() == null) {
            accessScope.requireAllCreditors(RESOURCE);
        } else {
            accessScope.requireCreditor(policy.getCreditorId());
        }
        return policy;
    }

    private boolean visible(AssistantPolicy p) {
        return p.getCreditorId() == null ? accessScope.hasAllCreditors() : accessScope.canAccessCreditor(p.getCreditorId());
    }

    private PolicyView view(AssistantPolicy p) {
        return new PolicyView(p.getId(), p.getCode(), p.getName(), p.getCreditorId(), p.getPortfolioId(),
                p.getPolicyVersion(), p.getStatus().name(), p.getMode().name(), codec.settings(p.getSettings()),
                p.getPromptCode(), p.getPromptVersion(), p.getPublishedAt(), p.getPublishedBy(), p.getRetiredAt(),
                p.getRetireReason(), p.getCreatedAt(), p.getVersion() == null ? 0 : p.getVersion());
    }

    private static String normalizeCode(String code) {
        Guard.require(code != null && !code.isBlank(), "assistant_policy.code_invalid", "Código obrigatório");
        return code.trim().toLowerCase(Locale.ROOT);
    }

    private static AssistantMode parseMode(String mode) {
        try {
            return AssistantMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("assistant_policy.mode_invalid", "Modo inválido: " + mode);
        }
    }

    private static PolicySettings withMode(PolicySettings s, AssistantMode mode) {
        return new PolicySettings(mode, s.channels(), s.serviceHours(), s.authorityProfileId(), s.promptCode(),
                s.promptVersion(), s.tone(), s.maxTurns(), s.maxMisunderstandings(), s.minConfidence(),
                s.requireIdentityVerification(), s.maxIdentityAttempts(), s.allowedTopics(), s.forbiddenTopics(),
                s.extraTriggers(), s.requiredProfiles(), s.identificationMessage(), s.handoffMessage(),
                s.fallbackMessage(), s.approvalMessage(), s.limits(), s.reviewSampleRate(), s.allowCreditBureauMention())
                .normalized();
    }
}
