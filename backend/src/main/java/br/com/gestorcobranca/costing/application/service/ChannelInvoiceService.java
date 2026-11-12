package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore;
import br.com.gestorcobranca.costing.application.port.out.MarginReader;
import br.com.gestorcobranca.costing.domain.model.ChannelInvoice;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.ItemEstimate;
import br.com.gestorcobranca.costing.domain.repository.ChannelInvoiceRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Faturas de canal (desenho §3.5 e §13). As faturas e a estimativa da assessoria inteira são da assessoria: leitura e
 * escrita exigem acesso a todos os credores (quem tem escopo restrito vê só o ajuste já rateado aos seus credores, no
 * painel). Informar, substituir ou excluir refaz o ajuste do mês na hora, com o mesmo lock da apuração (409
 * {@code costing.refresh_running} se outra estiver em andamento — a gravação é desfeita).
 */
@Service
@Transactional
class ChannelInvoiceService implements ChannelInvoiceUseCases {

    /** Meses por consulta (2 anos de histórico e mais um pouco para comparar exercícios). */
    static final int MAX_MONTHS = 36;
    private static final int DEFAULT_MONTHS = 24;

    private final ChannelInvoiceRepository invoices;
    private final CostFactStore store;
    private final MarginReader reader;
    private final CostRefreshService refresh;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;
    private final AuditTrail audit;
    private final Clock clock;

    ChannelInvoiceService(ChannelInvoiceRepository invoices, CostFactStore store, MarginReader reader,
                          CostRefreshService refresh, CurrentActor currentActor, AccessScope accessScope,
                          AccessLogger accessLogger, AuditTrail audit, Clock clock) {
        this.invoices = invoices;
        this.store = store;
        this.reader = reader;
        this.refresh = refresh;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> invoices(YearMonth from, YearMonth to) {
        requireRead();
        YearMonth[] range = range(from, to);
        List<ChannelInvoice> list = invoices.findAllByMonthBetweenOrderByMonthDescItemAsc(range[0].atDay(1),
                range[1].atDay(1));
        Map<YearMonth, Map<ChannelItem, ItemEstimate>> estimates = list.isEmpty() ? Map.of() : estimates(range);
        return list.stream().map(i -> view(i, estimates.getOrDefault(i.getMonth(), Map.of())
                .getOrDefault(i.getItem(), ItemEstimate.ZERO))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemEstimateView> estimates(YearMonth from, YearMonth to) {
        requireRead();
        YearMonth[] range = range(from, to);
        Map<YearMonth, Map<ChannelItem, ItemEstimate>> estimates = estimates(range);
        Map<YearMonth, Map<ChannelItem, ChannelInvoice>> byMonth = new HashMap<>();
        invoices.findAllByMonthBetweenOrderByMonthDescItemAsc(range[0].atDay(1), range[1].atDay(1))
                .forEach(i -> byMonth.computeIfAbsent(i.getMonth(), k -> new EnumMap<>(ChannelItem.class))
                        .put(i.getItem(), i));
        List<ItemEstimateView> out = new ArrayList<>();
        for (YearMonth m = range[1]; !m.isBefore(range[0]); m = m.minusMonths(1)) {
            for (ChannelItem item : ChannelItem.values()) {
                ItemEstimate e = estimates.getOrDefault(m, Map.of()).getOrDefault(item, ItemEstimate.ZERO);
                ChannelInvoice invoice = byMonth.getOrDefault(m, Map.of()).get(item);
                out.add(new ItemEstimateView(m.toString(), item, money(e.amount()),
                        e.quantity().setScale(4, RoundingMode.HALF_EVEN), unit(item), invoice == null ? null
                        : invoice.getId(), invoice == null ? null : invoice.getAmount()));
            }
        }
        return out;
    }

    @Override
    public InvoiceView save(SaveInvoiceCommand command) {
        requireWrite();
        Guard.requireNonNull(command, "costing.invoice_required", "Informe a fatura");
        Guard.requireNonNull(command.month(), "costing.invoice_month_required", "Informe o mês da fatura");
        Guard.requireNonNull(command.item(), "costing.invoice_item_required", "Informe o item da fatura");
        Guard.require(!command.month().isAfter(currentMonth()), "costing.invoice_future_month",
                "A fatura é de um mês que já começou: escolha o mês corrente ou um anterior");
        // fatos de mensagem gravados antes da conciliação por item não dizem de qual canal são
        Guard.requireState(command.item().category() != CostCategory.MESSAGING
                        || !store.hasUnitemizedMessaging(command.month()), "costing.invoice_month_not_itemized",
                "As mensagens deste mês foram apuradas antes da conciliação por canal: reapure o mês em Margem por "
                        + "carteira e informe a fatura de novo");
        ChannelInvoice existing = invoices.findByMonthAndItem(command.month().atDay(1), command.item()).orElse(null);
        ChannelInvoice saved;
        if (existing == null) {
            saved = invoices.saveAndFlush(new ChannelInvoice(command.month(), command.item(), command.amount(),
                    command.supplier(), command.note()));
            audit.record(AuditEntry.of("costing.invoice_created", "channel_invoice", saved.getId())
                    .created("month", saved.getMonth()).created("item", saved.getItem())
                    .created("amount", saved.getAmount()).created("supplier", saved.getSupplier())
                    .created("note", saved.getNote()));
        } else {
            BigDecimal amountBefore = existing.getAmount();
            String supplierBefore = existing.getSupplier();
            String noteBefore = existing.getNote();
            existing.replace(command.amount(), command.supplier(), command.note());
            saved = invoices.saveAndFlush(existing);
            // a substituição fica na auditoria com o antes e o depois (campos iguais não entram)
            audit.record(AuditEntry.of("costing.invoice_changed", "channel_invoice", saved.getId())
                    .meta("month", saved.getMonth()).meta("item", saved.getItem())
                    .change("amount", amountBefore, saved.getAmount())
                    .change("supplier", supplierBefore, saved.getSupplier())
                    .change("note", noteBefore, saved.getNote()));
        }
        refresh.reconcileInvoices(saved.getMonth());
        YearMonth m = saved.getMonth();
        return view(saved, estimates(new YearMonth[] {m, m}).getOrDefault(m, Map.of())
                .getOrDefault(saved.getItem(), ItemEstimate.ZERO));
    }

    @Override
    public void delete(UUID invoiceId, String reason) {
        requireWrite();
        ChannelInvoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> ResourceNotFoundException.of("channel_invoice", invoiceId));
        invoices.delete(invoice);
        audit.record(AuditEntry.of("costing.invoice_deleted", "channel_invoice", invoice.getId())
                .change("month", invoice.getMonth(), null).change("item", invoice.getItem(), null)
                .change("amount", invoice.getAmount(), null).change("supplier", invoice.getSupplier(), null)
                .change("note", invoice.getNote(), null)
                .reason(reason == null || reason.isBlank() ? null : reason.trim()));
        refresh.reconcileInvoices(invoice.getMonth());
    }

    // ================================================================== apoio

    private Map<YearMonth, Map<ChannelItem, ItemEstimate>> estimates(YearMonth[] range) {
        Map<YearMonth, Map<ChannelItem, ItemEstimate>> out = new HashMap<>();
        reader.channelEstimates(range[0].atDay(1), range[1].atEndOfMonth()).forEach(r -> out
                .computeIfAbsent(r.month(), k -> new EnumMap<>(ChannelItem.class))
                .merge(r.item(), r.estimate(), ItemEstimate::plus));
        return out;
    }

    /** Período em meses: nulos = os últimos 24 meses até o corrente; no máximo {@link #MAX_MONTHS}. */
    private YearMonth[] range(YearMonth from, YearMonth to) {
        YearMonth end = to != null ? to : (from != null ? from.plusMonths(DEFAULT_MONTHS - 1L) : currentMonth());
        YearMonth start = from != null ? from : end.minusMonths(DEFAULT_MONTHS - 1L);
        Guard.require(!start.isAfter(end), "costing.period_invalid", "O início deve ser antes do fim");
        Guard.require(ChronoUnit.MONTHS.between(start, end) < MAX_MONTHS, "costing.invoice_period_too_long",
                "Consulte no máximo " + MAX_MONTHS + " meses por vez");
        return new YearMonth[] {start, end};
    }

    private YearMonth currentMonth() {
        return YearMonth.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    /** Leitura: {@code COST_VIEW} ou {@code COST_MANAGE} e todos os credores (a fatura é da assessoria). */
    private void requireRead() {
        Actor actor = currentActor.get();
        if (!actor.has(Permission.COST_VIEW) && !actor.has(Permission.COST_MANAGE)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("resource",
                    "costing_invoices", "permission", "COST_VIEW|COST_MANAGE")));
            throw new ScopeAccessDeniedException();
        }
        accessScope.requireAllCreditors("costing_invoices");
    }

    private void requireWrite() {
        accessScope.requirePermission(Permission.COST_MANAGE);
        accessScope.requireAllCreditors("costing_invoices");
    }

    private static String unit(ChannelItem item) {
        return switch (item) {
            case TELEPHONY -> "MINUTES";
            case WHATSAPP, SMS, EMAIL -> "UNITS";
            case AI -> "USD";
        };
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static InvoiceView view(ChannelInvoice i, ItemEstimate estimate) {
        return new InvoiceView(i.getId(), i.getMonth().toString(), i.getItem(), i.getAmount(), i.getSupplier(),
                i.getNote(), money(estimate.amount()), money(i.getAmount().subtract(estimate.amount())),
                i.getUpdatedAt());
    }
}
