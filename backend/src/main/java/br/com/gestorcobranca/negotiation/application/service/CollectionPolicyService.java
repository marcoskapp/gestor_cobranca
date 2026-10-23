package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases;
import br.com.gestorcobranca.negotiation.domain.model.policy.CollectionPolicy;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyStatus;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.negotiation.domain.repository.CollectionPolicyRepository;
import br.com.gestorcobranca.negotiation.domain.repository.MonetaryIndexRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class CollectionPolicyService implements CollectionPolicyUseCases {

    private static final String RESOURCE = "collection_policy";

    private final CollectionPolicyRepository policies;
    private final MonetaryIndexRepository indexes;
    private final PolicyTermsCodec codec;
    private final PolicyResolver resolver;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final NegotiationMetrics metrics;
    private final Clock clock;

    CollectionPolicyService(CollectionPolicyRepository policies, MonetaryIndexRepository indexes, PolicyTermsCodec codec,
                            PolicyResolver resolver, CreditorUseCases creditors, PortfolioUseCases portfolios,
                            AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                            BusinessEventLogger businessEvents, NegotiationMetrics metrics, Clock clock) {
        this.policies = policies;
        this.indexes = indexes;
        this.codec = codec;
        this.resolver = resolver;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public PolicyView createDraft(CreatePolicyCommand command) {
        accessScope.requirePermission(Permission.POLICY_MANAGE);
        Guard.require(command.creditorId() != null, "policy.creditor_required", "Credor obrigatório");
        accessScope.requireCreditor(command.creditorId());
        creditors.get(command.creditorId());
        Guard.require(command.terms() != null, "policy.terms_required", "Termos obrigatórios");
        if (policies.maxVersion(command.creditorId()) > 0) {
            throw new ConflictException("policy.already_exists",
                    "O credor já tem política; crie uma nova versão a partir da vigente");
        }
        String terms = encodeChecked(command.creditorId(), command.terms());
        CollectionPolicy policy = policies.save(new CollectionPolicy(command.creditorId(), 1, command.name(),
                command.description(), terms, PolicyTerms.SCHEMA_VERSION));
        auditTrail.record(AuditEntry.of("collection_policy.created", RESOURCE, policy.getId())
                .creditor(policy.getCreditorId()).created("version", 1).created("name", policy.getName())
                .created("terms", policy.getTerms()));
        metrics.policyTransition(PolicyStatus.DRAFT.name());
        return view(policy);
    }

    @Override
    public PolicyView updateDraft(UUID policyId, UpdatePolicyCommand command) {
        accessScope.requirePermission(Permission.POLICY_MANAGE);
        CollectionPolicy policy = loadForCommand(policyId);
        Guard.require(command.terms() != null, "policy.terms_required", "Termos obrigatórios");
        String before = policy.getTerms();
        AuditEntry audit = AuditEntry.of("collection_policy.updated", RESOURCE, policyId).creditor(policy.getCreditorId())
                .change("name", policy.getName(), command.name() == null ? null : command.name().trim())
                .change("description", policy.getDescription(), command.description());
        policy.describe(command.name(), command.description());
        policy.defineTerms(encodeChecked(policy.getCreditorId(), command.terms()), PolicyTerms.SCHEMA_VERSION);
        policies.save(policy);
        auditTrail.record(audit.change("terms", before, policy.getTerms()));
        return view(policy);
    }

    @Override
    public PolicyView newVersion(UUID policyId) {
        accessScope.requirePermission(Permission.POLICY_MANAGE);
        CollectionPolicy source = loadForCommand(policyId);
        List<CollectionPolicy> versions = policies.findByCreditor(source.getCreditorId());
        Guard.requireState(versions.stream().noneMatch(p -> p.getStatus() == PolicyStatus.DRAFT), "policy.draft_exists",
                "Já existe um rascunho da política deste credor");
        int next = versions.getFirst().getPolicyVersion() + 1;
        CollectionPolicy draft = policies.save(new CollectionPolicy(source.getCreditorId(), next, source.getName(),
                source.getDescription(), codec.encode(codec.decode(source)), PolicyTerms.SCHEMA_VERSION));
        auditTrail.record(AuditEntry.of("collection_policy.version_created", RESOURCE, draft.getId())
                .creditor(draft.getCreditorId()).created("version", next).meta("sourcePolicyId", source.getId()));
        metrics.policyTransition(PolicyStatus.DRAFT.name());
        return view(draft);
    }

    @Override
    public PolicyView publish(UUID policyId, LocalDate effectiveFrom) {
        accessScope.requirePermission(Permission.POLICY_MANAGE);
        CollectionPolicy policy = loadForCommand(policyId);
        PolicyTerms terms = codec.decode(policy);
        // índices e carteiras podem ter mudado desde o rascunho
        encodeChecked(policy.getCreditorId(), terms);
        LocalDate today = today();
        Instant now = Instant.now(clock);
        policy.publish(effectiveFrom, today, currentActor.actorId(), now);
        for (CollectionPolicy previous : policies.findEffectiveCandidates(policy.getCreditorId())) {
            if (previous.getId().equals(policy.getId())) {
                continue;
            }
            LocalDate before = previous.getEffectiveTo();
            if (previous.supersede(effectiveFrom)) {
                policies.save(previous);
                auditTrail.record(AuditEntry.of("collection_policy.superseded", RESOURCE, previous.getId())
                        .creditor(previous.getCreditorId()).change("effectiveTo", before, previous.getEffectiveTo())
                        .meta("byPolicyId", policy.getId()));
            }
        }
        policies.save(policy);
        auditTrail.record(AuditEntry.of("collection_policy.published", RESOURCE, policy.getId())
                .creditor(policy.getCreditorId()).change("status", PolicyStatus.DRAFT, PolicyStatus.PUBLISHED)
                .created("effectiveFrom", policy.getEffectiveFrom()).meta("version", policy.getPolicyVersion()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.POLICY_PUBLISHED)
                .creditor(policy.getCreditorId())
                .reference(RESOURCE, policy.getId())
                .attr("policyKind", "collection")
                .attr("version", policy.getPolicyVersion())
                .attr("effectiveFrom", policy.getEffectiveFrom().toString())
                .attr("overrides", terms.overrides().size())
                .attr("ladderLevels", terms.base().ladder().size())
                .attr("maxInstallments", terms.base().installments().maxInstallments())
                .attr("interestMode", terms.base().charges().interestMode())
                .attr("hasCorrection", terms.base().charges().correctionIndexCode() != null));
        metrics.policyTransition(PolicyStatus.PUBLISHED.name());
        return view(policy);
    }

    @Override
    public PolicyView retire(UUID policyId, LocalDate effectiveTo) {
        accessScope.requirePermission(Permission.POLICY_MANAGE);
        CollectionPolicy policy = loadForCommand(policyId);
        LocalDate before = policy.getEffectiveTo();
        policy.retire(effectiveTo, today(), currentActor.actorId(), Instant.now(clock));
        policies.save(policy);
        auditTrail.record(AuditEntry.of("collection_policy.retired", RESOURCE, policyId).creditor(policy.getCreditorId())
                .change("status", PolicyStatus.PUBLISHED, PolicyStatus.RETIRED)
                .change("effectiveTo", before, policy.getEffectiveTo()));
        metrics.policyTransition(PolicyStatus.RETIRED.name());
        return view(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyView get(UUID policyId) {
        accessScope.requirePermission(Permission.POLICY_VIEW);
        return view(loadVisible(policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PolicyView> search(UUID creditorId, String status, PageQuery page) {
        accessScope.requirePermission(Permission.POLICY_VIEW);
        PolicyStatus statusFilter = status == null || status.isBlank() ? null : parseStatus(status);
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null
                : accessScope.restrictCreditors(creditorId == null ? List.of() : List.of(creditorId));
        return PageResponse.of(policies.search(allowed, creditorId, statusFilter,
                page.toPageable(Sort.by(Sort.Order.asc("creditorId"), Sort.Order.desc("policyVersion")))), this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public EffectivePolicyView effective(UUID creditorId, UUID portfolioId, Long daysOverdue, LocalDate date) {
        accessScope.requirePermission(Permission.POLICY_VIEW);
        Guard.require(creditorId != null, "policy.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        LocalDate on = date == null ? today() : date;
        PolicyResolver.ResolvedPolicy resolved = resolver.effective(creditorId, on)
                .orElseThrow(() -> new ResourceNotFoundException("collection_policy_effective", creditorId));
        PolicyTerms.Resolved rules = resolved.terms().resolve(portfolioId, daysOverdue == null ? 0 : daysOverdue);
        CollectionPolicy p = resolved.policy();
        return new EffectivePolicyView(p.getId(), p.getCreditorId(), p.getPolicyVersion(), p.getEffectiveFrom(),
                p.getEffectiveTo(), rules.overrideCode(), rules.rules());
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyTerms template() {
        accessScope.requirePermission(Permission.POLICY_VIEW);
        return StandardPolicyTerms.marketDefault();
    }

    /** Valida os termos, as carteiras das sobreposições (do credor) e os índices (existentes), e serializa. */
    private String encodeChecked(UUID creditorId, PolicyTerms terms) {
        String encoded = codec.encode(terms);
        Set<UUID> referenced = terms.referencedPortfolios();
        if (!referenced.isEmpty()) {
            Map<UUID, PortfolioView> found = portfolios.getAll(referenced).stream()
                    .collect(Collectors.toMap(PortfolioView::id, Function.identity()));
            for (UUID id : referenced) {
                PortfolioView portfolio = found.get(id);
                Guard.require(portfolio != null && creditorId.equals(portfolio.creditorId()),
                        "policy.terms.override_portfolio_invalid", "Carteira " + id + " não pertence ao credor");
            }
        }
        for (String code : terms.referencedIndexes()) {
            Guard.require(indexes.existsByCode(code), "policy.terms.index_unknown",
                    "Índice de correção " + code + " não cadastrado");
        }
        return encoded;
    }

    private CollectionPolicy loadVisible(UUID policyId) {
        CollectionPolicy policy = policies.findById(policyId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, policyId));
        accessScope.requireVisible(RESOURCE, policyId, policy.getCreditorId());
        return policy;
    }

    private CollectionPolicy loadForCommand(UUID policyId) {
        CollectionPolicy policy = loadVisible(policyId);
        accessScope.requireCreditor(policy.getCreditorId());
        return policy;
    }

    private static PolicyStatus parseStatus(String status) {
        try {
            return PolicyStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("policy.status_invalid", "Status inválido: " + status);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private PolicyView view(CollectionPolicy p) {
        return new PolicyView(p.getId(), p.getCreditorId(), p.getPolicyVersion(), p.getName(), p.getDescription(),
                p.getStatus().name(), p.getEffectiveFrom(), p.getEffectiveTo(), codec.decode(p), p.getTermsSchemaVersion(),
                p.getPublishedAt(), p.getPublishedBy(), p.getRetiredAt(), p.getCreatedAt());
    }
}
