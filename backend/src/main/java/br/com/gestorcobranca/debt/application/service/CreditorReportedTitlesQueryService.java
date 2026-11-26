package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.CreditorReportedTitlesQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.out.CreditorReportedTitlesReader;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
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

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aba "Pela carga do credor" da lista "Baixas informadas pelo credor" (H07 parte 2b; RS-94 a RS-98, RS-103 a RS-107).
 * Somente leitura, sem auditoria; nome do devedor como nas outras listas (só o nome de exibição, sem
 * {@code SENSITIVE_DATA_VIEWED}).
 */
@Service
@Transactional(readOnly = true)
class CreditorReportedTitlesQueryService implements CreditorReportedTitlesQueries {

    static final String RESOURCE = "creditor_reported";
    static final SortableFields SORT = SortableFields.builder()
            .field("occurredAt", "occurred_at")
            .field("amount", "amount")
            .build();
    private static final Set<String> SITUATIONS = Set.of("SETTLED_BY_IMPORT", "REPORTED_PAID", "REPORTED_CANCELLED");

    private final CreditorReportedTitlesReader reader;
    private final DebtorUseCases debtors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final DebtMetrics metrics;

    CreditorReportedTitlesQueryService(CreditorReportedTitlesReader reader, DebtorUseCases debtors,
                                   AccessScope accessScope, CurrentActor currentActor, DebtMetrics metrics) {
        this.reader = reader;
        this.debtors = debtors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.metrics = metrics;
    }

    @Override
    public ReportedTitlesPage search(ReportedTitleFilter filter, PageQuery page) {
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
        PageQuery p = page == null ? PageQuery.of(null, null) : page;
        Guard.require(p.size() <= MAX_PAGE_SIZE, "creditor_reported.page_size_invalid",
                "Até %d linhas por página".formatted(MAX_PAGE_SIZE));
        String situation = filter.situation() == null || filter.situation().isBlank() ? null : filter.situation();
        Guard.require(situation == null || SITUATIONS.contains(situation), "title.situation_invalid",
                "Situação inválida");
        if (filter.creditorId() != null) {
            accessScope.requireVisible("creditor", filter.creditorId(), filter.creditorId());
        }
        Sort sort = SORT.resolve(p.sort(), Sort.by(Sort.Order.desc("occurred_at"), Sort.Order.desc("title_id")));
        // o filtro por lote só vale para quem pode ver as cargas; sem a permissão é ignorado (RC-03)
        UUID importBatchId = actor.has(Permission.IMPORT_VIEW) ? filter.importBatchId() : null;
        List<CreditorReportedTitlesReader.Order> order = sort.stream()
                .map(o -> new CreditorReportedTitlesReader.Order("id".equals(o.getProperty()) ? "title_id"
                        : o.getProperty(), o.isAscending())).toList();
        CreditorReportedTitlesReader.Criteria criteria = new CreditorReportedTitlesReader.Criteria(
                accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of()), filter.creditorId(),
                filter.from().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                filter.to().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(), importBatchId,
                situation);
        List<CreditorReportedTitlesReader.Row> rows = reader.page(criteria, order, (long) p.page() * p.size(),
                p.size());
        CreditorReportedTitlesReader.Totals totals = reader.totals(criteria);
        List<ReportedTitleItem> items = items(rows, actor);
        metrics.creditorReportedQuery(situation == null ? "ALL" : situation, items.isEmpty() ? "empty" : "ok");
        int totalPages = (int) ((totals.count() + p.size() - 1) / p.size());
        return new ReportedTitlesPage(new PageResponse<>(items, p.page(), p.size(), totals.count(), totalPages),
                new Totals(totals.count(), totals.amount()));
    }

    /** Minimização no servidor: referência, nome e lote só para quem tem a permissão de cada um (RS-96, RS-97). */
    private List<ReportedTitleItem> items(List<CreditorReportedTitlesReader.Row> rows, Actor actor) {
        if (rows.isEmpty()) {
            return List.of();
        }
        boolean showDebtor = actor.has(Permission.DEBTOR_VIEW);
        boolean showTitle = actor.has(Permission.DEBTOR_VIEW) || actor.has(Permission.CASE_VIEW);
        boolean showBatch = actor.has(Permission.IMPORT_VIEW);
        Set<UUID> debtorIds = rows.stream().map(CreditorReportedTitlesReader.Row::debtorId).collect(Collectors.toSet());
        // o nome (e a situação do titular) vem pela própria regra de visibilidade do devedor
        Map<UUID, DebtorUseCases.DebtorName> names = debtors.names(debtorIds).stream()
                .collect(Collectors.toMap(DebtorUseCases.DebtorName::id, Function.identity(), (a, b) -> a));
        return rows.stream().map(r -> {
            DebtorUseCases.DebtorName name = names.get(r.debtorId());
            boolean anonymized = name != null && "ANONYMIZED".equals(name.status());
            // titular anonimizado: a referência do credor pode conter o documento (decisão 1472) — não sai daqui
            String reference = showTitle && !anonymized ? reference(r) : null;
            return new ReportedTitleItem(r.titleId(), reference, r.creditorId(), r.portfolioId(), r.debtorId(),
                    showDebtor && name != null ? name.name() : null, anonymized, r.situation(),
                    showBatch ? r.importBatchId() : null, r.occurredAt(), r.amount(),
                    r.titleStatus());
        }).toList();
    }

    private static String reference(CreditorReportedTitlesReader.Row r) {
        if (r.externalId() == null) {
            return null;
        }
        return r.installmentNumber() == null ? r.externalId() : r.externalId() + "/" + r.installmentNumber();
    }
}
