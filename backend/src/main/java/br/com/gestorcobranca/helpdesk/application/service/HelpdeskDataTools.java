package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.CardView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.FieldView;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.helpdesk.domain.service.ReferenceBook.Kind;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementSummary;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.BatchSearch;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases.ItemView;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases.PendingWorkSummary;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementFilter;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Consultas somente leitura do chat, sempre pelos casos de uso dos módulos donos e no contexto de segurança da
 * requisição — ou seja, <strong>como o próprio usuário</strong>: permissões, escopo por credor e RLS são os das
 * telas (o {@link HelpdeskToolbox} confere antes a mesma permissão exigida pelo endpoint da tela e registra a
 * consulta na trilha de acesso).
 * <p>
 * Minimização: ao modelo voltam só referências opacas, situações, contagens, valores e datas necessários para
 * explicar a situação; nome e documento do devedor vão apenas para os cartões montados aqui e exibidos direto ao
 * usuário (documento já mascarado pelo módulo dono — a revelação, que exige {@code DEBTOR_SENSITIVE_VIEW}, nunca é
 * usada). Nunca vão: contatos, endereço, observações, evidências, referências externas, chaves e nomes de arquivo.
 */
@Component
class HelpdeskDataTools {

    static final int MAX_RESULTS = 5;

    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final AgreementUseCases agreements;
    private final PaymentUseCases payments;
    private final ImportBatchUseCases imports;
    private final SettlementStatementUseCases settlementStatements;
    private final StatementUseCases commissionStatements;
    private final PendingWorkUseCases pendingWork;
    private final CreditorUseCases creditors;
    private final KnowledgeBase knowledge;
    private final Clock clock;

    HelpdeskDataTools(DebtorUseCases debtors, CaseUseCases cases, AgreementUseCases agreements, PaymentUseCases payments,
                      ImportBatchUseCases imports, SettlementStatementUseCases settlementStatements,
                      StatementUseCases commissionStatements, PendingWorkUseCases pendingWork, CreditorUseCases creditors,
                      KnowledgeBase knowledge, Clock clock) {
        this.debtors = debtors;
        this.cases = cases;
        this.agreements = agreements;
        this.payments = payments;
        this.imports = imports;
        this.settlementStatements = settlementStatements;
        this.commissionStatements = commissionStatements;
        this.pendingWork = pendingWork;
        this.creditors = creditors;
        this.knowledge = knowledge;
        this.clock = clock;
    }

    /**
     * Resultado de uma consulta: o que vai ao modelo e os ids consultados (trilha de acesso).
     *
     * @param resource tipo de registro consultado
     */
    record DataResult(Map<String, Object> forModel, String resource, List<UUID> ids) {
    }

    /** Erro de argumento que a IA pode corrigir (referência desconhecida, texto curto…). */
    static final class ToolArgumentException extends RuntimeException {
        private final String code;

        ToolArgumentException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    // ------------------------------------------------------------------ devedores

    DataResult findDebtors(String text, TurnState state) {
        String query = text == null ? "" : text.trim();
        String document = state.placeholders.resolve(query);
        List<DebtorView> found;
        if (document != null) {
            found = debtors.findByDocument(document).map(List::of).orElse(List.of());
        } else {
            if (query.length() < 3 || query.contains("[")) {
                throw new ToolArgumentException("texto_curto", "Informe ao menos 3 letras do nome ou o marcador [DOCn]");
            }
            found = debtors.search(query, null, null, PageQuery.of(0, MAX_RESULTS)).content();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DebtorView d : found.stream().limit(MAX_RESULTS).toList()) {
            String ref = state.references.refFor(Kind.DEBTOR, d.id());
            out.add(map("ref", ref, "tipoPessoa", d.personType(), "situacao", d.status()));
            if (d.name() != null) {
                state.shownNames.add(d.name());
            }
            state.card(new CardView(ref, "DEBTOR", d.name(), d.maskedDocument(), d.status(), List.of(),
                    route("debt.debtor-detail", d.id()), Map.of()));
        }
        return new DataResult(map("encontrados", out.size(), "devedores", out,
                "observacao", "Nome e documento aparecem para a pessoa nos cartões; use as referências."),
                "debtor", found.stream().map(DebtorView::id).toList());
    }

    // ------------------------------------------------------------------ casos

    DataResult debtorCases(String debtorRef, TurnState state) {
        UUID debtorId = resolve(state, Kind.DEBTOR, debtorRef, "devedor");
        List<CaseView> list = cases.listByDebtor(debtorId);
        Map<UUID, String> names = creditorNames(list.stream().map(CaseView::creditorId).toList());
        Instant now = clock.instant();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CaseView c : list.stream().limit(MAX_RESULTS).toList()) {
            String ref = state.references.refFor(Kind.CASE, c.id());
            Map<String, Object> m = map("ref", ref, "devedor", debtorRef, "credor", names.get(c.creditorId()),
                    "situacao", c.status(), "motivoSituacao", c.statusReason(), "elegivel", c.eligible(),
                    "motivosInelegibilidade", c.eligibilityReasons(), "canaisContataveis", c.contactableChannels(),
                    "saldoEmAberto", c.openBalance(), "saldoElegivel", c.eligibleBalance(), "diasDeAtraso", c.daysOverdue(),
                    "titulosEmAberto", c.openTitles(), "titulosContestados", c.disputedTitles(),
                    "titulosSuspensos", c.suspendedTitles(), "responsavelEhVoce", state.actor.id().equals(c.assigneeUserId()),
                    "tipoResponsavel", c.ownerType(), "proximaAcao", Formats.isoDate(c.nextActionAt()),
                    "ultimaInteracao", Formats.isoDate(c.lastInteractionAt()), "abertoEm", Formats.isoDate(c.openedAt()),
                    "encerradoEm", Formats.isoDate(c.closedAt()), "motivoEncerramento", c.closeReason(),
                    "score", c.score());
            out.add(m);
            state.card(new CardView(ref, "CASE", "Caso " + ref, names.get(c.creditorId()), c.status(), List.of(
                    new FieldView("Saldo em aberto", Formats.money(c.openBalance())),
                    new FieldView("Dias de atraso", String.valueOf(c.daysOverdue())),
                    new FieldView("Elegível", c.eligible() ? "Sim" : "Não")),
                    route("collection.case", c.id()), Map.of()));
        }
        return new DataResult(map("casos", out, "total", list.size(), "consultadoEm", now.toString()), "collection_case",
                list.stream().map(CaseView::id).toList());
    }

    // ------------------------------------------------------------------ acordos

    DataResult agreements(String debtorRef, String caseRef, TurnState state) {
        UUID debtorId = debtorRef == null || debtorRef.isBlank() ? null : resolve(state, Kind.DEBTOR, debtorRef, "devedor");
        UUID caseId = caseRef == null || caseRef.isBlank() ? null : resolve(state, Kind.CASE, caseRef, "caso");
        if (debtorId == null && caseId == null) {
            throw new ToolArgumentException("referencia_obrigatoria", "Informe o devedor (D…) ou o caso (C…)");
        }
        List<AgreementView> list = agreements.search(new AgreementFilter(null, debtorId, caseId, null),
                PageQuery.of(0, MAX_RESULTS)).content();
        Map<UUID, String> names = creditorNames(list.stream().map(AgreementView::creditorId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgreementView a : list) {
            String ref = state.references.refFor(Kind.AGREEMENT, a.id());
            String debtor = state.references.refFor(Kind.DEBTOR, a.debtorId());
            List<InstallmentView> open = a.installments().stream()
                    .filter(i -> !"PAID".equals(i.status()) && !"CANCELLED".equals(i.status())).toList();
            Optional<InstallmentView> next = open.stream().min(java.util.Comparator.comparing(InstallmentView::number));
            long overdue = a.installments().stream().filter(i -> "OVERDUE".equals(i.status())).count();
            Map<String, Object> m = map("ref", ref, "devedor", debtor, "credor", names.get(a.creditorId()),
                    "situacao", a.status(), "motivoSituacao", a.statusReason(), "origem", a.origin(),
                    "valorTotal", a.totalAmount(), "entrada", a.downPayment(), "pago", a.paidAmount(),
                    "restante", a.remainingAmount(), "desconto", a.discountAmount(), "parcelas", a.installments().size(),
                    "parcelasEmAberto", open.size(), "parcelasVencidas", overdue,
                    "regraDeQuebra", a.breakRule() == null ? null : map("parcelasEmAtraso", a.breakRule().overdueInstallments(),
                            "toleranciaDias", a.breakRule().toleranceDays()),
                    "formalizadoEm", Formats.isoDate(a.formalizedAt()), "quitadoEm", Formats.isoDate(a.settledAt()),
                    "encerradoEm", Formats.isoDate(a.endedAt()), "renegociacao", a.renegotiationNumber());
            next.ifPresent(i -> m.put("proximaParcela", map("numero", i.number(), "vencimento", String.valueOf(i.dueDate()),
                    "valor", i.amount(), "pago", i.paidAmount(), "situacao", i.status())));
            out.add(m);
            List<FieldView> fields = new ArrayList<>(List.of(
                    new FieldView("Total", Formats.money(a.totalAmount())),
                    new FieldView("Pago", Formats.money(a.paidAmount())),
                    new FieldView("Restante", Formats.money(a.remainingAmount())),
                    new FieldView("Parcelas", open.size() + " em aberto de " + a.installments().size())));
            next.ifPresent(i -> fields.add(new FieldView("Próxima parcela", i.number() + " — " + Formats.date(i.dueDate()))));
            state.card(new CardView(ref, "AGREEMENT", "Acordo " + ref, names.get(a.creditorId()), a.status(), fields,
                    route("negotiation.agreement-detail", a.id()), Map.of()));
        }
        return new DataResult(map("acordos", out, "total", out.size()), "negotiation_agreement",
                list.stream().map(AgreementView::id).toList());
    }

    // ------------------------------------------------------------------ pagamentos

    DataResult payments(String debtorRef, String agreementRef, TurnState state) {
        UUID debtorId = debtorRef == null || debtorRef.isBlank() ? null : resolve(state, Kind.DEBTOR, debtorRef, "devedor");
        UUID agreementId = agreementRef == null || agreementRef.isBlank() ? null
                : resolve(state, Kind.AGREEMENT, agreementRef, "acordo");
        if (debtorId == null && agreementId == null) {
            throw new ToolArgumentException("referencia_obrigatoria", "Informe o devedor (D…) ou o acordo (A…)");
        }
        List<PaymentView> list = payments.search(new PaymentFilter(null, debtorId, null, null, null, null, null, null,
                agreementId), PageQuery.of(0, 10)).content();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PaymentView p : list) {
            String ref = state.references.refFor(Kind.PAYMENT, p.id());
            out.add(map("ref", ref, "devedor", p.debtorId() == null ? null : state.references.refFor(Kind.DEBTOR, p.debtorId()),
                    "situacao", p.status(), "origem", p.source(), "meio", p.method(), "valor", p.amount(),
                    "pagoEm", Formats.isoDate(p.paidAt()), "confirmadoEm", Formats.isoDate(p.confirmedAt()),
                    "conciliadoEm", Formats.isoDate(p.reconciledAt()), "estornadoEm", Formats.isoDate(p.reversedAt()),
                    "revisao", p.reviewMode(), "aguardaAprovacao", "INFORMED".equals(p.status()),
                    "alocado", p.allocatedAmount(), "naoAlocado", p.unallocatedAmount(),
                    "aguardandoRealocacao", p.pendingReallocation()));
            state.card(new CardView(ref, "PAYMENT", "Pagamento " + ref, p.method(), p.status(), List.of(
                    new FieldView("Valor", Formats.money(p.amount())),
                    new FieldView("Pago em", Formats.dateTime(p.paidAt()))),
                    route("payment.detail", p.id()), Map.of()));
        }
        return new DataResult(map("pagamentos", out, "total", out.size()), "payment",
                list.stream().map(PaymentView::id).toList());
    }

    // ------------------------------------------------------------------ importações

    DataResult imports(String batchRef, TurnState state) {
        List<ImportBatchView> list;
        if (batchRef != null && !batchRef.isBlank()) {
            list = List.of(imports.get(resolve(state, Kind.IMPORT, batchRef, "importação")));
        } else {
            Instant since = clock.instant().minus(Duration.ofDays(30));
            list = imports.search(new BatchSearch(null, null, null, null, since, null), PageQuery.of(0, MAX_RESULTS))
                    .content();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ImportBatchView b : list) {
            String ref = state.references.refFor(Kind.IMPORT, b.id());
            ImportBatchUseCases.Counters c = b.counters();
            out.add(map("ref", ref, "situacao", b.status(), "motivoSituacao", b.statusReason(), "origem", b.source(),
                    "modo", b.mode(), "layout", b.layoutCode(), "progressoPercentual", b.progressPercent(),
                    "linhas", b.totalRows(), "linhasLidas", c == null ? null : c.rowsRead(),
                    "linhasValidas", c == null ? null : c.rowsValid(), "linhasComErro", c == null ? null : c.rowsWithError(),
                    "avisos", c == null ? null : c.warnings(), "titulosCriados", c == null ? null : c.titlesCreated(),
                    "titulosAtualizados", c == null ? null : c.titlesUpdated(), "forcada", b.forced(),
                    "enviadaPorVoce", state.actor.id().equals(b.requestedBy()), "temConferencia", b.reviewId() != null,
                    "criadaEm", Formats.isoDate(b.createdAt()), "concluidaEm", Formats.isoDate(b.finishedAt())));
            state.card(new CardView(ref, "IMPORT", "Importação " + ref, Formats.dateTime(b.createdAt()), b.status(),
                    c == null ? List.of() : List.of(new FieldView("Linhas válidas", String.valueOf(c.rowsValid())),
                            new FieldView("Linhas com erro", String.valueOf(c.rowsWithError()))),
                    route("ingestion.batch-detail", b.id()), Map.of()));
        }
        return new DataResult(map("importacoes", out, "total", out.size(), "periodo", "últimos 30 dias"),
                "import_batch", list.stream().map(ImportBatchView::id).toList());
    }

    // ------------------------------------------------------------------ demonstrativos (prestação de contas)

    DataResult settlementStatements(String status, TurnState state) {
        List<StatementView> list = settlementStatements.search(new StatementFilter(null, blankToNull(status)),
                PageQuery.of(0, MAX_RESULTS)).content();
        Map<UUID, String> names = creditorNames(list.stream().map(StatementView::creditorId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (StatementView s : list) {
            String ref = state.references.refFor(Kind.SETTLEMENT_STATEMENT, s.id());
            var t = s.totals();
            out.add(map("ref", ref, "credor", names.get(s.creditorId()), "numero", s.sequenceNumber(),
                    "periodo", s.periodStart() + " a " + s.periodEnd(), "situacao", s.status(),
                    "recuperado", t == null ? null : t.recovered(), "comissao", t == null ? null : t.commission(),
                    "liquidoARepassar", t == null ? null : t.netTransfer(), "repassado", t == null ? null : t.transferred(),
                    "pendenteConciliacao", t == null ? null : t.pendingReconciliation(),
                    "divergenciasAbertas", t == null ? null : t.openDivergences(),
                    "aprovadoEm", Formats.isoDate(s.approvedAt()), "enviadoEm", Formats.isoDate(s.sentAt())));
            state.card(new CardView(ref, "SETTLEMENT_STATEMENT", "Demonstrativo nº " + s.sequenceNumber(),
                    names.get(s.creditorId()), s.status(), List.of(
                    new FieldView("Período", Formats.date(s.periodStart()) + " a " + Formats.date(s.periodEnd())),
                    new FieldView("Líquido a repassar", Formats.money(t == null ? null : t.netTransfer()))),
                    route("settlement.statement", s.id()), Map.of()));
        }
        return new DataResult(map("demonstrativos", out, "total", out.size()), "settlement_statement",
                list.stream().map(StatementView::id).toList());
    }

    // ------------------------------------------------------------------ extratos de remuneração (só os próprios)

    DataResult myCommissionStatements(String period, TurnState state) {
        YearMonth month = null;
        if (period != null && !period.isBlank()) {
            try {
                month = YearMonth.parse(period.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new ToolArgumentException("periodo_invalido", "Período no formato AAAA-MM");
            }
        }
        List<StatementSummary> list = commissionStatements.list(month, state.actor.id(), null, PageQuery.of(0, 6)).content();
        List<Map<String, Object>> out = new ArrayList<>();
        for (StatementSummary s : list) {
            String ref = state.references.refFor(Kind.COMMISSION_STATEMENT, s.id());
            out.add(map("ref", ref, "periodo", String.valueOf(s.period()), "situacao", s.status(), "resultado", s.outcome(),
                    "plano", s.planCode(), "calculado", s.calculatedAmount(), "ajustes", s.adjustmentsAmount(),
                    "aPagar", s.payableAmount(), "retido", s.withheldAmount(), "recuperacaoCreditada", s.creditedRecovery(),
                    "aprovadoEm", Formats.isoDate(s.approvedAt())));
            state.card(new CardView(ref, "COMMISSION_STATEMENT", "Extrato " + s.period(), null, s.status(), List.of(
                    new FieldView("A pagar", Formats.money(s.payableAmount()))),
                    route("incentive.statement", s.id()), Map.of()));
        }
        return new DataResult(map("extratos", out, "total", out.size(), "observacao", "Somente os extratos da própria pessoa."),
                "commission_statement", list.stream().map(StatementSummary::id).toList());
    }

    // ------------------------------------------------------------------ pendências (agregador do pendingwork)

    DataResult myPendingWork(TurnState state) {
        PendingWorkSummary summary = pendingWork.mine();
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (ItemView item : summary.items()) {
            out.add(map("tipo", item.type(), "area", item.area(), "descricao", item.label(), "orientacao", item.hint(),
                    "quantidade", item.count(), "vencidas", item.overdue(), "venceHoje", item.dueToday(),
                    "severidade", item.severity(), "desde", Formats.isoDate(item.oldestAt()),
                    "prazoMaisProximo", item.nextDueDate() == null ? null : item.nextDueDate().toString()));
            if (n++ < 8) {
                state.card(new CardView(null, "PENDING", item.label(), item.area(), item.severity(), List.of(
                        new FieldView("Quantidade", String.valueOf(item.count())),
                        new FieldView("Vencidas", String.valueOf(item.overdue()))), item.route(), item.queryParams()));
            }
        }
        return new DataResult(map("total", summary.total(), "vencidas", summary.overdue(), "venceHoje", summary.dueToday(),
                "pendencias", out, "indisponiveis", summary.unavailable().stream().map(u -> u.area()).toList()),
                "pending_work", List.of());
    }

    // ------------------------------------------------------------------ apoio

    private UUID resolve(TurnState state, Kind kind, String ref, String label) {
        return state.references.resolve(kind, ref).orElseThrow(() -> new ToolArgumentException("referencia_desconhecida",
                "Referência de " + label + " desconhecida nesta conversa: consulte antes para obter a referência"));
    }

    private String route(String screenKey, UUID id) {
        return knowledge.screen(screenKey).map(s -> s.route(id.toString())).filter(r -> !r.contains(":")).orElse(null);
    }

    private Map<UUID, String> creditorNames(Collection<UUID> ids) {
        Set<UUID> distinct = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            return creditors.getAll(distinct).stream().collect(Collectors.toMap(CreditorUseCases.CreditorView::id,
                    c -> c.tradeName() == null ? c.legalName() : c.tradeName(), (a, b) -> a));
        } catch (DomainException | br.com.gestorcobranca.shared.security.ScopeAccessDeniedException e) {
            return Map.of();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Mapa ordenado sem valores nulos (o modelo recebe só o que existe). */
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                m.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return m;
    }
}
