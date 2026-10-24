package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases;
import br.com.gestorcobranca.assistant.application.port.out.QualityStatistics;
import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.ReviewStatus;
import br.com.gestorcobranca.assistant.domain.model.ReviewVerdict;
import br.com.gestorcobranca.assistant.domain.model.TurnReview;
import br.com.gestorcobranca.assistant.domain.repository.AssistantPolicyRepository;
import br.com.gestorcobranca.assistant.domain.repository.TurnReviewRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class AssistantReviewService implements AssistantReviewUseCases {

    /** Origens da atribuição em que o assistente participou. */
    static final Set<String> ASSISTANT_ORIGINS = Set.of("ASSISTANT_AUTONOMOUS", "ASSISTANT_HANDOFF_CONFIRMED",
            "ASSISTANT_HANDOFF_NEGOTIATED");
    static final Set<String> HUMAN_ORIGINS = Set.of("HUMAN", "HUMAN_COPILOT");

    private final TurnReviewRepository reviews;
    private final AssistantPolicyRepository policies;
    private final QualityStatistics statistics;
    private final AssistantCodec codec;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final AssistantMetrics metrics;
    private final AssistantProperties properties;
    private final Clock clock;

    AssistantReviewService(TurnReviewRepository reviews, AssistantPolicyRepository policies, QualityStatistics statistics,
                           AssistantCodec codec, AccessScope accessScope, CurrentActor currentActor,
                           AuditTrail auditTrail, BusinessEventLogger businessEvents, AssistantMetrics metrics,
                           AssistantProperties properties, Clock clock) {
        this.reviews = reviews;
        this.policies = policies;
        this.statistics = statistics;
        this.codec = codec;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReviewView> queue(String status, UUID creditorId, PageQuery page) {
        accessScope.requirePermission(Permission.ASSISTANT_REVIEW);
        ReviewStatus s = status == null || status.isBlank() ? ReviewStatus.PENDING : parse(ReviewStatus.class, status);
        var pageable = page.toPageable(Sort.by(Sort.Order.asc("createdAt")));
        Page<TurnReview> result;
        if (creditorId != null) {
            if (!accessScope.canAccessCreditor(creditorId)) {
                return PageResponse.of(Page.empty());
            }
            result = reviews.findByStatusAndCreditorIdIn(s, Set.of(creditorId), pageable);
        } else if (accessScope.hasAllCreditors()) {
            result = reviews.findByStatus(s, pageable);
        } else {
            Set<UUID> allowed = accessScope.restrictCreditors(null);
            if (allowed.isEmpty()) {
                return PageResponse.of(Page.empty());
            }
            result = reviews.findByStatusAndCreditorIdIn(s, allowed, pageable);
        }
        return PageResponse.of(result, this::view);
    }

    @Override
    public ReviewView submit(UUID reviewId, ReviewCommand command) {
        accessScope.requirePermission(Permission.ASSISTANT_REVIEW);
        Guard.require(command != null, "assistant_review.required", "Avaliação obrigatória");
        TurnReview review = reviews.findById(reviewId).orElseThrow(() -> ResourceNotFoundException.of("assistant_review", reviewId));
        accessScope.requireVisible("assistant_review", reviewId, review.getCreditorId());
        accessScope.requireCreditor(review.getCreditorId());
        UUID reviewer = currentActor.actorId();
        if (review.getPolicyId() != null) {
            AssistantPolicy policy = policies.findById(review.getPolicyId()).orElse(null);
            if (policy != null && reviewer.equals(policy.getPublishedBy())) {
                throw new ConflictException("assistant_review.own_policy",
                        "Quem publicou a política não avalia os turnos dela");
            }
        }
        ReviewVerdict verdict = parse(ReviewVerdict.class, command.verdict());
        Set<String> tags = new LinkedHashSet<>();
        for (String t : command.tags() == null ? List.<String>of() : command.tags()) {
            String tag = t == null ? "" : t.trim().toUpperCase(Locale.ROOT);
            Guard.require(TurnReview.TAGS.contains(tag), "assistant_review.tag_invalid", "Etiqueta inválida: " + t);
            tags.add(tag);
        }
        review.review(reviewer, command.score(), verdict, codec.toJson(List.copyOf(tags)), command.comment(),
                Instant.now(clock));
        reviews.save(review);
        auditTrail.record(AuditEntry.of("assistant_review.recorded", "assistant_review", review.getId())
                .creditor(review.getCreditorId()).change("status", ReviewStatus.PENDING, ReviewStatus.REVIEWED)
                .meta("turnId", review.getTurnId()).meta("score", command.score()).meta("verdict", verdict)
                .meta("tags", List.copyOf(tags)));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.ASSISTANT_REVIEW_RECORDED).creditor(review.getCreditorId())
                .portfolio(review.getPortfolioId()).reference("assistant_turn", review.getTurnId())
                .attr("score", command.score()).attr("verdict", verdict).attr("sampleReason", review.getSampleReason())
                .attr("tags", String.join(",", tags))
                .attr("policyId", review.getPolicyId() == null ? null : review.getPolicyId().toString()));
        metrics.review(verdict.name());
        return view(review);
    }

    @Override
    @Transactional(readOnly = true)
    public QualityView quality(QualityQuery query) {
        accessScope.requirePermission(Permission.ASSISTANT_REVIEW);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate to = query == null || query.to() == null ? today : query.to();
        LocalDate from = query == null || query.from() == null ? to.minusDays(29) : query.from();
        Guard.require(!from.isAfter(to), "assistant_quality.period_invalid", "Período inválido");
        Guard.require(!from.isBefore(to.minusDays(366)), "assistant_quality.period_too_long", "Período de até 1 ano");
        UUID creditorId = query == null ? null : query.creditorId();
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        QualityStatistics.Snapshot s = statistics.snapshot(from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(), allowed, creditorId,
                query == null ? null : query.portfolioId(), properties.earlyBreakDays());
        long handoffs = s.handoffsByReason().values().stream().mapToLong(Long::longValue).sum();
        Map<String, Long> byGroup = new LinkedHashMap<>();
        Map<String, BigDecimal> earlyRate = new LinkedHashMap<>();
        for (String group : List.of("ASSISTANT", "HUMAN")) {
            Set<String> origins = group.equals("ASSISTANT") ? ASSISTANT_ORIGINS : HUMAN_ORIGINS;
            long formalized = sum(s.agreementsByOrigin(), origins);
            long early = sum(s.earlyBreaksByOrigin(), origins);
            byGroup.put(group, formalized);
            earlyRate.put(group, formalized == 0 ? null : ratio(early, formalized));
        }
        long complaints = s.handoffsByReason().entrySet().stream()
                .filter(e -> HandoffReason.parse(e.getKey()).map(HandoffReason::complaint).orElse(false))
                .mapToLong(Map.Entry::getValue).sum();
        long assistantAgreements = byGroup.get("ASSISTANT");
        BigDecimal costPerAgreement = assistantAgreements == 0 ? null
                : s.cost().divide(BigDecimal.valueOf(assistantAgreements), 6, RoundingMode.HALF_EVEN);
        return new QualityView(from, to, s.turns(), s.conversations(), s.outcomes(), s.handoffsByReason(),
                s.conversations() == 0 ? null : ratio(handoffs, s.conversations()), s.guardrails(), s.agreementsByOrigin(),
                byGroup, earlyRate, properties.earlyBreakDays(), complaints, s.cost(), s.costCurrency(), costPerAgreement,
                s.inputTokens(), s.outputTokens(), s.avgLatencyMs(), s.reviewed(), s.avgScore(), s.outOfPolicy(),
                s.pendingReviews());
    }

    private ReviewView view(TurnReview r) {
        return new ReviewView(r.getId(), r.getTurnId(), r.getConversationId(), r.getCreditorId(), r.getPortfolioId(),
                r.getPolicyId(), r.getSampleReason().name(), r.getStatus().name(), r.getScore(),
                r.getVerdict() == null ? null : r.getVerdict().name(), codec.strings(r.getTags()), r.getComment(),
                r.getReviewerId(), r.getReviewedAt(), r.getCreatedAt());
    }

    private static long sum(Map<String, Long> map, Set<String> keys) {
        return map.entrySet().stream().filter(e -> keys.contains(e.getKey())).mapToLong(Map.Entry::getValue).sum();
    }

    private static BigDecimal ratio(long a, long b) {
        return BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 4, RoundingMode.HALF_EVEN);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("assistant_review.value_invalid", "Valor inválido: " + value);
        }
    }
}
