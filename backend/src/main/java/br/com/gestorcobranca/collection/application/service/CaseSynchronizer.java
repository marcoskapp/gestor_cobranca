package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.out.DebtorCaseLock;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseBalance;
import br.com.gestorcobranca.collection.domain.model.CloseReason;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.TaskStatus;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionTaskRepository;
import br.com.gestorcobranca.collection.domain.service.QueueRouter;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorSegmentationQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtorSegmentationQueries.DebtorSegmentation;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mantém os casos coerentes com os títulos (F06): agrega os títulos em cobrança de um devedor por
 * carteira, abre o caso quando surge título em aberto numa carteira sem caso ativo, atualiza a
 * posição, encerra o caso que ficou sem títulos em cobrança e reavalia elegibilidade (RB02) e
 * fila. Executa na transação do chamador (consumidor de evento, job ou caso de uso).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CaseSynchronizer {

    private static final Set<String> IN_COLLECTION = Set.of("OPEN", "SUSPENDED");

    private final CollectionCaseRepository cases;
    private final CollectionQueueRepository queues;
    private final CollectionTaskRepository tasks;
    private final DebtTitleUseCases titles;
    private final EligibilityEvaluator eligibility;
    private final CaseRecorder recorder;
    private final CallbackRecorder callbacks;
    private final DebtorCaseLock lock;
    private final CurrentActor currentActor;
    private final VulnerabilityQueries vulnerability;
    private final DebtorSegmentationQueries segmentation;
    private final Clock clock;

    CaseSynchronizer(CollectionCaseRepository cases, CollectionQueueRepository queues, CollectionTaskRepository tasks,
                     DebtTitleUseCases titles, EligibilityEvaluator eligibility, CaseRecorder recorder,
                     CallbackRecorder callbacks, DebtorCaseLock lock, CurrentActor currentActor,
                     VulnerabilityQueries vulnerability, DebtorSegmentationQueries segmentation, Clock clock) {
        this.segmentation = segmentation;
        this.cases = cases;
        this.queues = queues;
        this.tasks = tasks;
        this.titles = titles;
        this.eligibility = eligibility;
        this.recorder = recorder;
        this.callbacks = callbacks;
        this.lock = lock;
        this.currentActor = currentActor;
        this.vulnerability = vulnerability;
        this.clock = clock;
    }

    /** Contexto de uma execução (filas ativas e carteiras carregadas uma vez). */
    Context context() {
        return new Context(queues.findByActiveTrue(), eligibility.batch(), null, null);
    }

    /**
     * Contexto de um lote de devedores (reprocessamento da carteira): o marcador de vulnerabilidade é consultado uma
     * vez para todos, em vez de uma consulta por caso aberto.
     */
    Context context(java.util.Collection<UUID> debtorIds) {
        return new Context(queues.findByActiveTrue(), eligibility.batch(), vulnerability.vulnerableAmong(debtorIds),
                segmentationOf(debtorIds));
    }

    /** Tipo de pessoa e novidade dos contatos dos devedores (debt), em consultas de até {@code MAX_DEBTORS}. */
    private Map<UUID, DebtorSegmentation> segmentationOf(java.util.Collection<UUID> debtorIds) {
        Map<UUID, DebtorSegmentation> result = new HashMap<>();
        List<UUID> ids = List.copyOf(new java.util.LinkedHashSet<>(debtorIds));
        for (int from = 0; from < ids.size(); from += DebtorSegmentationQueries.MAX_DEBTORS) {
            result.putAll(segmentation.segmentation(
                    ids.subList(from, Math.min(ids.size(), from + DebtorSegmentationQueries.MAX_DEBTORS))));
        }
        return result;
    }

    /** Estado atual no debt (fonte da verdade do tipo de pessoa e do fim da novidade do caso); vazio se fora do escopo. */
    Optional<DebtorSegmentation> debtorSegmentation(UUID debtorId) {
        return Optional.ofNullable(segmentation.segmentation(List.of(debtorId)).get(debtorId));
    }

    /**
     * Fim da novidade dos contatos do devedor mudou (debt): guarda no caso, com o tipo de pessoa atual, e refaz só o
     * roteamento (os critérios "só devedores com contato novo" e "tipo de pessoa" podem mudar a fila); a elegibilidade
     * não depende deles. Qualquer das duas mudanças passa pelo {@code recorder.commit}.
     *
     * @return true se o valor do caso mudou
     */
    boolean applyNewContact(CollectionCase c, DebtorSegmentation current, String trigger) {
        if (!c.getStatus().isActive()) {
            return false;
        }
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        String previousPersonType = c.getDebtorPersonType();
        c.identifyDebtorPersonType(current.personType());
        boolean personTypeChanged = !Objects.equals(previousPersonType, c.getDebtorPersonType());
        boolean noveltyChanged = c.applyNewContactUntil(current.newContactUntil());
        if (!personTypeChanged && !noveltyChanged) {
            return false;
        }
        c.routeTo(QueueRouter.route(c, context().queues(), CollectionViews.today(clock), Instant.now(clock))
                .map(CollectionQueue::getId).orElse(null));
        recorder.commit(c, before, AssignmentMode.ROUTING, null, trigger);
        return true;
    }

    /** Estado atual do marcador no compliance (fonte da verdade do indicador do caso). */
    boolean debtorIsVulnerable(UUID debtorId) {
        return vulnerability.isVulnerable(debtorId);
    }

    /**
     * Sincroniza todos os casos do devedor no credor com a posição atual dos títulos.
     *
     * @return casos ativos ou encerrados nesta sincronização
     */
    List<CollectionCase> syncDebtor(UUID debtorId, UUID creditorId, String trigger, Context ctx) {
        lock.lock(currentActor.tenantId(), debtorId);
        List<TitleView> creditorTitles = titles.listByDebtor(debtorId).stream()
                .filter(t -> t.creditorId().equals(creditorId)).toList();
        Map<UUID, List<TitleView>> byPortfolio = creditorTitles.stream()
                .collect(Collectors.groupingBy(TitleView::portfolioId));
        Map<UUID, CollectionCase> active = new HashMap<>();
        cases.findActiveByDebtorAndCreditor(debtorId, creditorId).forEach(c -> active.put(c.getPortfolioId(), c));
        Instant now = Instant.now(clock);
        List<CollectionCase> touched = new ArrayList<>();
        for (Map.Entry<UUID, List<TitleView>> entry : byPortfolio.entrySet()) {
            CaseBalance balance = balance(entry.getValue());
            CollectionCase existing = active.remove(entry.getKey());
            if (existing != null) {
                touched.add(balance.inCollection() ? update(existing, balance, trigger, ctx)
                        : close(existing, closeReason(entry.getValue()), "Sem títulos em cobrança na carteira", trigger));
            } else if (balance.openTitles() > 0) {
                touched.add(open(debtorId, creditorId, entry.getKey(), balance, now, trigger, ctx));
            }
        }
        // casos de carteiras sem nenhum título do devedor (ex.: títulos transferidos de carteira)
        for (CollectionCase orphan : active.values()) {
            touched.add(close(orphan, CloseReason.NO_OPEN_TITLES, "Títulos não pertencem mais à carteira", trigger));
        }
        return touched;
    }

    /** Ressincroniza a posição de um caso específico (e os demais do devedor no mesmo credor). */
    Optional<CollectionCase> syncCase(CollectionCase c, String trigger) {
        List<CollectionCase> touched = syncDebtor(c.getDebtorId(), c.getCreditorId(), trigger, context());
        return touched.stream().filter(t -> t.getId().equals(c.getId())).findFirst()
                .or(() -> cases.findById(c.getId()));
    }

    /**
     * Atualiza em memória a posição do caso com os títulos atuais da sua carteira (sem abrir nem
     * encerrar casos; usado quando o próprio módulo altera títulos, como na contestação).
     */
    void refreshPosition(CollectionCase c) {
        List<TitleView> portfolioTitles = titles.listByDebtor(c.getDebtorId()).stream()
                .filter(t -> t.creditorId().equals(c.getCreditorId()) && t.portfolioId().equals(c.getPortfolioId()))
                .toList();
        c.updateBalance(balance(portfolioTitles), Instant.now(clock));
    }

    /**
     * Títulos em cobrança (OPEN/SUSPENDED com saldo) da carteira do caso, para seleção de títulos
     * de uma contestação.
     */
    List<TitleView> titlesInCollection(CollectionCase c) {
        return titles.listByDebtor(c.getDebtorId()).stream()
                .filter(t -> t.creditorId().equals(c.getCreditorId()) && t.portfolioId().equals(c.getPortfolioId()))
                .filter(t -> IN_COLLECTION.contains(t.status()) && t.openBalance() != null && t.openBalance().signum() > 0)
                .toList();
    }

    /**
     * Reavalia elegibilidade e fila de um caso ativo (restrição de contato, política, carteira,
     * contestação, mudança de estágio).
     */
    CollectionCase reevaluate(CollectionCase c, String trigger, Context ctx) {
        if (!c.getStatus().isActive()) {
            return c;
        }
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        applyEligibilityAndRoute(c, ctx);
        return recorder.commit(c, before, AssignmentMode.ROUTING, null, trigger);
    }

    /**
     * Marcador de vulnerabilidade do devedor mudou (compliance): guarda o indicador no caso e refaz só o roteamento
     * (o critério "só devedores com marcador" pode mudar a fila); a elegibilidade não depende do marcador.
     *
     * @return true se o indicador do caso mudou
     */
    boolean applyVulnerability(CollectionCase c, boolean vulnerable, String trigger) {
        if (!c.getStatus().isActive()) {
            return false;
        }
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        if (!c.markVulnerable(vulnerable)) {
            return false;
        }
        c.routeTo(QueueRouter.route(c, context().queues(), CollectionViews.today(clock), Instant.now(clock))
                .map(CollectionQueue::getId).orElse(null));
        recorder.commit(c, before, AssignmentMode.ROUTING, null, trigger);
        return true;
    }

    /**
     * Score de propensão novo (módulo scoring): guarda no caso e reavalia só a fila — o critério de
     * faixa de score pode mudar o roteamento; a elegibilidade não depende do score. Cálculo mais
     * antigo que o aplicado é ignorado.
     *
     * @return true se o score do caso mudou
     */
    boolean applyScore(CollectionCase c, int score, Instant calculatedAt, String trigger) {
        if (!c.getStatus().isActive()) {
            return false;
        }
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        if (!c.applyScore(score, calculatedAt)) {
            cases.save(c);
            return false;
        }
        c.routeTo(QueueRouter.route(c, context().queues(), CollectionViews.today(clock), Instant.now(clock))
                .map(CollectionQueue::getId).orElse(null));
        recorder.commit(c, before, AssignmentMode.ROUTING, null, trigger);
        return true;
    }

    /** Recalcula elegibilidade e fila no caso em memória (sem persistir; o chamador faz o commit). */
    void applyEligibilityAndRoute(CollectionCase c, Context ctx) {
        Instant now = Instant.now(clock);
        c.applyEligibility(ctx.eligibility().evaluate(c), now);
        UUID queueId = QueueRouter.route(c, ctx.queues(), CollectionViews.today(clock), now).map(CollectionQueue::getId)
                .orElse(null);
        c.routeTo(queueId);
    }

    private CollectionCase open(UUID debtorId, UUID creditorId, UUID portfolioId, CaseBalance balance, Instant now,
                                String trigger, Context ctx) {
        CollectionCase c = new CollectionCase(debtorId, creditorId, portfolioId, balance, now);
        // devedor já marcado como vulnerável: o caso nasce com o indicador (critério de fila própria)
        c.markVulnerable(ctx.vulnerableDebtors() != null ? ctx.vulnerableDebtors().contains(debtorId)
                : vulnerability.isVulnerable(debtorId));
        // tipo de pessoa e contato novo do devedor (critérios de fila); depois, a novidade chega por evento
        Optional<DebtorSegmentation> debtor = ctx.segmentation() != null
                ? Optional.ofNullable(ctx.segmentation().get(debtorId)) : debtorSegmentation(debtorId);
        debtor.ifPresent(d -> {
            c.identifyDebtorPersonType(d.personType());
            c.applyNewContactUntil(d.newContactUntil());
        });
        // id definido antes da avaliação (contestações são consultadas pelo caso; aqui não há nenhuma)
        applyEligibilityAndRoute(c, ctx);
        return recorder.opened(c, trigger);
    }

    private CollectionCase update(CollectionCase c, CaseBalance balance, String trigger, Context ctx) {
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        c.updateBalance(balance, Instant.now(clock));
        applyEligibilityAndRoute(c, ctx);
        return recorder.commit(c, before, AssignmentMode.ROUTING, null, trigger);
    }

    /** Encerra o caso: cancela as tarefas em aberto e fecha o período de dono do atendimento. */
    CollectionCase close(CollectionCase c, CloseReason reason, String note, String trigger) {
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        Instant now = Instant.now(clock);
        for (CollectionTask task : tasks.findByCaseIdAndStatusIn(c.getId(), EnumSet.of(TaskStatus.PENDING, TaskStatus.MISSED))) {
            task.cancel("Caso encerrado");
            tasks.save(task);
            callbacks.cancelled(task, c);
        }
        c.close(reason, note, now);
        return recorder.commit(c, before, AssignmentMode.CLOSED, note, trigger);
    }

    /**
     * Posição da carteira a partir dos títulos: em cobrança = OPEN/SUSPENDED com saldo; elegível =
     * OPEN sem bloqueio por contestação.
     */
    static CaseBalance balance(List<TitleView> portfolioTitles) {
        Money open = Money.ZERO;
        Money eligible = Money.ZERO;
        int openTitles = 0;
        int suspended = 0;
        int disputed = 0;
        LocalDate oldest = null;
        for (TitleView t : portfolioTitles) {
            Money balance = Money.ofNullable(t.openBalance());
            if (!IN_COLLECTION.contains(t.status()) || !balance.isPositive()) {
                continue;
            }
            open = open.plus(balance);
            if ("OPEN".equals(t.status())) {
                openTitles++;
                if (t.disputeBlocked()) {
                    disputed++;
                } else {
                    eligible = eligible.plus(balance);
                }
            } else {
                suspended++;
            }
            if (oldest == null || t.dueDate().isBefore(oldest)) {
                oldest = t.dueDate();
            }
        }
        return new CaseBalance(open, eligible, openTitles, suspended, disputed, oldest);
    }

    /** Motivo do encerramento automático pelo destino dos títulos: todos quitados, devolvidos ou cancelados. */
    static CloseReason closeReason(List<TitleView> portfolioTitles) {
        Set<String> statuses = portfolioTitles.stream().map(TitleView::status).collect(Collectors.toSet());
        if (statuses.equals(Set.of("SETTLED"))) {
            return CloseReason.SETTLED;
        }
        if (statuses.equals(Set.of("RETURNED"))) {
            return CloseReason.RETURNED;
        }
        if (statuses.equals(Set.of("CANCELLED"))) {
            return CloseReason.CANCELLED;
        }
        return CloseReason.NO_OPEN_TITLES;
    }

    /**
     * @param vulnerableDebtors devedores com marcador já consultados em lote (nulo = consultar caso a caso)
     * @param segmentation      tipo de pessoa e novidade dos contatos já consultados em lote (nulo = consultar caso a caso)
     */
    record Context(List<CollectionQueue> queues, EligibilityEvaluator.Batch eligibility,
                   java.util.Set<UUID> vulnerableDebtors, Map<UUID, DebtorSegmentation> segmentation) {

        Context {
            queues = queues.stream().sorted(Comparator.comparingInt(CollectionQueue::getPriority)
                    .thenComparing(CollectionQueue::getCode)).toList();
            Objects.requireNonNull(eligibility);
        }
    }
}
