package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases;
import br.com.gestorcobranca.payment.application.port.out.CreditorReportedPaymentsReader;
import br.com.gestorcobranca.payment.application.port.spi.IntegrationTokenLabels;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.model.TargetKind;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.paging.SortableFields;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aba "Pagamentos informados pelo credor" da lista "Baixas informadas pelo credor" (H07 parte 2b; RS-94 a RS-107).
 * Somente leitura, sem auditoria nem log de negócio (como as demais listas de pagamentos); nome do devedor como nas
 * outras listas, sem {@code SENSITIVE_DATA_VIEWED} (só o nome de exibição, nunca o documento — decisão do H07 2b).
 */
@Service
@Transactional(readOnly = true)
class CreditorReportedPaymentsService implements CreditorReportedPaymentsUseCases {

    static final String RESOURCE = "creditor_reported";
    /** Ordenação: nome público → coluna (lista branca; desempate por id). */
    static final SortableFields SORT = SortableFields.builder()
            .field("paidAt", "paid_at")
            .field("amount", "amount")
            .build();
    private static final Set<String> CHANNELS = Set.of(Payment.CHANNEL_INTEGRATION, Payment.CHANNEL_CREDITOR_PORTAL,
            Payment.CHANNEL_OPERATOR);

    private final CreditorReportedPaymentsReader reader;
    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final DebtorUseCases debtors;
    private final IntegrationTokenLabels tokenLabels;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final PaymentMetrics metrics;
    private final JsonCodec json;

    CreditorReportedPaymentsService(CreditorReportedPaymentsReader reader, PaymentRepository payments,
                              PaymentAllocationRepository allocations, DebtorUseCases debtors,
                              IntegrationTokenLabels tokenLabels, AccessScope accessScope, CurrentActor currentActor,
                              PaymentMetrics metrics, JsonCodec json) {
        this.reader = reader;
        this.payments = payments;
        this.allocations = allocations;
        this.debtors = debtors;
        this.tokenLabels = tokenLabels;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.json = json;
    }

    @Override
    public ReportedPaymentsPage search(ReportedPaymentFilter filter, PageQuery page) {
        Actor actor = currentActor.get();
        if (actor.isCreditor() || actor.type() == Actor.ActorType.INTEGRATION) {
            throw accessScope.denyActorType(RESOURCE);
        }
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        Guard.require(filter != null && filter.from() != null && filter.to() != null,
                "creditor_reported.period_required", "Informe o período (de e até)");
        Guard.require(!filter.to().isBefore(filter.from())
                        && ChronoUnit.DAYS.between(filter.from(), filter.to()) <= MAX_PERIOD_DAYS,
                "creditor_reported.period_invalid",
                "Período inválido: a data final não pode ser anterior à inicial nem passar de %d dias"
                        .formatted(MAX_PERIOD_DAYS));
        requireAmountRange(filter.minAmount(), filter.maxAmount());
        PageQuery p = page == null ? PageQuery.of(null, null) : page;
        Guard.require(p.size() <= MAX_PAGE_SIZE, "creditor_reported.page_size_invalid",
                "Até %d linhas por página".formatted(MAX_PAGE_SIZE));
        String channel = filter.channel() == null || filter.channel().isBlank() ? null : filter.channel();
        Guard.require(channel == null || CHANNELS.contains(channel), "payment.channel_invalid", "Canal inválido");
        String status = PaymentEnums.name(PaymentEnums.parseOptional(PaymentStatus.class, filter.status(),
                "payment.status_invalid", "Situação"));
        if (filter.creditorId() != null) {
            // mesma recusa das outras leituras para credor fora do escopo (ou inexistente)
            accessScope.requireVisible("creditor", filter.creditorId(), filter.creditorId());
        }
        Sort sort = SORT.resolve(p.sort(), Sort.by(Sort.Order.desc("paid_at"), Sort.Order.desc("id")));
        // o filtro por token só vale para quem pode ver os tokens; sem a permissão é ignorado (RC-03)
        UUID tokenId = actor.has(Permission.PUBLIC_API_TOKEN_VIEW) ? filter.tokenId() : null;

        CreditorReportedPaymentsReader.Criteria criteria = new CreditorReportedPaymentsReader.Criteria(allowedCreditors(),
                filter.creditorId(), filter.from().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                filter.to().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(), channel, tokenId,
                filter.minAmount(), filter.maxAmount(), status);
        List<CreditorReportedPaymentsReader.Order> order = sort.stream()
                .map(o -> new CreditorReportedPaymentsReader.Order(o.getProperty(), o.isAscending())).toList();
        List<UUID> ids = reader.page(criteria, order, (long) p.page() * p.size(), p.size());
        CreditorReportedPaymentsReader.Totals totals = reader.totals(criteria);
        List<ReportedPaymentItem> items = items(ids, actor);
        metrics.creditorReportedQuery("payments", channel == null ? "ALL" : channel, items.isEmpty() ? "empty" : "ok");
        int totalPages = p.size() == 0 ? 0 : (int) ((totals.count() + p.size() - 1) / p.size());
        return new ReportedPaymentsPage(new PageResponse<>(items, p.page(), p.size(), totals.count(), totalPages),
                new Totals(totals.count(), totals.amount(), totals.effectiveAmount()));
    }

    private static void requireAmountRange(BigDecimal min, BigDecimal max) {
        Guard.require((min == null || min.signum() >= 0) && (max == null || max.signum() >= 0)
                        && (min == null || max == null || min.compareTo(max) <= 0),
                "creditor_reported.amount_range_invalid",
                "Faixa de valor inválida: valores não negativos, com o mínimo até o máximo");
    }

    /** Linhas da página com os dados que o perfil de quem consulta pode ver (minimização no servidor). */
    private List<ReportedPaymentItem> items(List<UUID> ids, Actor actor) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Payment> byId = payments.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));
        List<Payment> rows = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        Map<UUID, List<PaymentAllocation>> allocationsByPayment = allocations.findByPaymentIdIn(ids).stream()
                .collect(Collectors.groupingBy(PaymentAllocation::getPaymentId));
        boolean showDebtor = actor.has(Permission.DEBTOR_VIEW);
        boolean showTitles = actor.has(Permission.DEBTOR_VIEW) || actor.has(Permission.CASE_VIEW);
        boolean showAgreement = actor.has(Permission.AGREEMENT_VIEW);
        boolean showToken = actor.has(Permission.PUBLIC_API_TOKEN_VIEW);
        Map<UUID, DebtorUseCases.DebtorName> names = showDebtor ? debtorNames(rows) : Map.of();
        // rótulos só dos tokens das linhas visíveis (RS-95)
        Set<UUID> tokenIds = showToken ? rows.stream().filter(Payment::isFromIntegration).map(Payment::getInformedBy)
                .collect(Collectors.toSet()) : Set.of();
        Map<UUID, IntegrationTokenLabels.TokenLabel> labels = tokenIds.isEmpty() ? Map.of() : tokenLabels.labels(tokenIds);
        List<ReportedPaymentItem> items = new ArrayList<>();
        for (Payment p : rows) {
            DebtorUseCases.DebtorName name = names.get(p.getDebtorId());
            boolean anonymized = name != null && "ANONYMIZED".equals(name.status());
            List<UUID> titles = titles(p, allocationsByPayment.getOrDefault(p.getId(), List.of()));
            UUID agreementId = agreement(p, allocationsByPayment.getOrDefault(p.getId(), List.of()));
            boolean evidence = p.getEvidenceKey() != null;
            items.add(new ReportedPaymentItem(p.getId(), p.getPaidAt(), p.getInformedAt(), p.getCreditorId(),
                    p.getDebtorId(), name == null ? null : name.name(), anonymized,
                    showAgreement ? agreementId : null, showTitles ? titles : List.of(), titles.size(),
                    p.getAmount().amount(), PaymentEnums.name(p.getExternalOrigin()), p.informedChannel(),
                    showToken && p.isFromIntegration() ? token(p.getInformedBy(), labels) : null,
                    p.getStatus().name(), evidence, evidence && unverified(p)));
        }
        return items;
    }

    private Map<UUID, DebtorUseCases.DebtorName> debtorNames(List<Payment> rows) {
        Set<UUID> debtorIds = rows.stream().map(Payment::getDebtorId).collect(Collectors.toSet());
        return debtors.names(debtorIds).stream()
                .collect(Collectors.toMap(DebtorUseCases.DebtorName::id, Function.identity(), (a, b) -> a));
    }

    private static CreditorReportedPaymentsUseCases.TokenRef token(UUID tokenId,
                                                             Map<UUID, IntegrationTokenLabels.TokenLabel> labels) {
        IntegrationTokenLabels.TokenLabel label = labels.get(tokenId);
        return label == null
                ? new CreditorReportedPaymentsUseCases.TokenRef(tokenId, null, null, false, false,
                PaymentService.TOKEN_NOT_FOUND)
                : new CreditorReportedPaymentsUseCases.TokenRef(tokenId, label.name(), label.displayPrefix(),
                label.revoked(), true, label.state().name());
    }

    /**
     * Comprovante que ninguém da assessoria conferiu nem passou pela verificação de malware: integração (sempre) e
     * portal com veredito diferente de {@code CLEAN} (sem verificação, falha ou suspeito). O operador registrou com o
     * comprovante em mãos.
     */
    private static boolean unverified(Payment p) {
        if (Payment.CHANNEL_OPERATOR.equals(p.informedChannel())) {
            return false;
        }
        return !"CLEAN".equals(p.getEvidenceScanVerdict());
    }

    /** Títulos alocados (pagamento confirmado) ou, sem alocação, os pedidos no registro. */
    private List<UUID> titles(Payment p, List<PaymentAllocation> list) {
        Set<UUID> ids = new LinkedHashSet<>();
        list.stream().flatMap(a -> a.getLines().stream()).map(PaymentAllocation.Line::titleId)
                .filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty() && p.getTargetKind() == TargetKind.TITLES) {
            ids.addAll(PaymentViews.targetItems(p, json));
        }
        return List.copyOf(ids);
    }

    private static UUID agreement(Payment p, List<PaymentAllocation> list) {
        if (p.getTargetKind() == TargetKind.AGREEMENT && p.getTargetAgreementId() != null) {
            return p.getTargetAgreementId();
        }
        return list.stream().map(PaymentAllocation::getAgreementId).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Set<UUID> allowedCreditors() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
    }
}
