package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.QueueUseCases;
import br.com.gestorcobranca.collection.domain.model.CaseOrdering;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue.QueueDefinition;
import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class QueueService implements QueueUseCases {

    private final CollectionQueueRepository queues;
    private final CollectionCaseRepository cases;
    private final PortfolioUseCases portfolios;
    private final TeamUseCases teams;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final CaseSweepJob sweep;
    private final Clock clock;

    QueueService(CollectionQueueRepository queues, CollectionCaseRepository cases, PortfolioUseCases portfolios,
                 TeamUseCases teams, AccessScope accessScope, AuditTrail auditTrail, CaseSweepJob sweep, Clock clock) {
        this.queues = queues;
        this.cases = cases;
        this.portfolios = portfolios;
        this.teams = teams;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.sweep = sweep;
        this.clock = clock;
    }

    @Override
    public QueueView create(QueueCommand command) {
        Guard.require(command != null && command.code() != null, "queue.code_invalid", "Código da fila obrigatório");
        QueueDefinition definition = definition(command);
        requireQueueScope(definition.creditorId());
        CollectionQueue queue = new CollectionQueue(command.code(), definition);
        if (queues.existsByCode(queue.getCode())) {
            throw new ConflictException("queue.code_taken", "Já existe fila com o código " + queue.getCode());
        }
        CollectionQueue saved = queues.save(queue);
        auditTrail.record(AuditEntry.of("collection_queue.created", "collection_queue", saved.getId())
                .creditor(saved.getCreditorId())
                .created("code", saved.getCode())
                .created("name", saved.getName())
                .created("priority", saved.getPriority())
                .created("criteria", criteria(saved))
                .created("strategy", saved.getStrategy())
                .created("ordering", saved.getOrdering()));
        sweep.enqueue(null, null, "reroute", null);
        return CollectionViews.queue(saved);
    }

    @Override
    public QueueView update(UUID queueId, QueueCommand command) {
        CollectionQueue queue = load(queueId);
        requireQueueScope(queue.getCreditorId());
        QueueDefinition definition = definition(command);
        requireQueueScope(definition.creditorId());
        String beforeCriteria = criteria(queue);
        int beforePriority = queue.getPriority();
        DistributionStrategy beforeStrategy = queue.getStrategy();
        queue.define(definition);
        CollectionQueue saved = queues.save(queue);
        auditTrail.record(AuditEntry.of("collection_queue.updated", "collection_queue", saved.getId())
                .creditor(saved.getCreditorId())
                .change("criteria", beforeCriteria, criteria(saved))
                .change("priority", beforePriority, saved.getPriority())
                .change("strategy", beforeStrategy, saved.getStrategy()));
        sweep.enqueue(null, null, "reroute", null);
        return CollectionViews.queue(saved);
    }

    @Override
    public QueueView activate(UUID queueId) {
        return toggle(queueId, true);
    }

    @Override
    public QueueView deactivate(UUID queueId) {
        return toggle(queueId, false);
    }

    private QueueView toggle(UUID queueId, boolean active) {
        CollectionQueue queue = load(queueId);
        requireQueueScope(queue.getCreditorId());
        if (queue.isActive() == active) {
            return CollectionViews.queue(queue);
        }
        if (active) {
            queue.activate();
        } else {
            queue.deactivate();
        }
        CollectionQueue saved = queues.save(queue);
        auditTrail.record(AuditEntry.of(active ? "collection_queue.activated" : "collection_queue.deactivated",
                "collection_queue", saved.getId()).creditor(saved.getCreditorId()).change("active", !active, active));
        sweep.enqueue(null, null, "reroute", null);
        return CollectionViews.queue(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public QueueView get(UUID queueId) {
        return CollectionViews.queue(loadVisible(queueId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueView> list() {
        return queues.findAllByOrderByPriorityAscCodeAsc().stream()
                .filter(q -> q.getCreditorId() == null || accessScope.canAccessCreditor(q.getCreditorId()))
                .sorted(Comparator.comparingInt(CollectionQueue::getPriority).thenComparing(CollectionQueue::getCode))
                .map(CollectionViews::queue).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public QueueSummary summary(UUID queueId) {
        CollectionQueue queue = loadVisible(queueId);
        Instant now = Instant.now(clock);
        Map<UUID, Long> loads = cases.countActiveAssignedByUser(queue.getId());
        List<UserLoad> userLoads = loads.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new UserLoad(e.getKey(), e.getValue())).toList();
        return new QueueSummary(queue.getId(), cases.countAvailable(queue.getId(), now), cases.countWaiting(queue.getId()),
                loads.values().stream().mapToLong(Long::longValue).sum(), userLoads);
    }

    @Override
    public void reroute() {
        accessScope.requireAllCreditors("collection_queue");
        sweep.enqueue(null, null, "reroute", null);
    }

    private QueueDefinition definition(QueueCommand c) {
        Guard.require(c != null, "queue.definition_required", "Definição da fila obrigatória");
        if (c.portfolioId() != null) {
            UUID portfolioCreditor = portfolios.get(c.portfolioId()).creditorId();
            Guard.require(portfolioCreditor.equals(c.creditorId()), "queue.portfolio_creditor_mismatch",
                    "A carteira não pertence ao credor da fila");
        }
        c.teamIds().forEach(teams::get);
        return new QueueDefinition(c.name(), c.description(), c.priority(), c.creditorId(), c.portfolioId(),
                c.minDaysOverdue(), c.maxDaysOverdue(), money(c.minBalance()), money(c.maxBalance()), c.minScore(),
                c.maxScore(),
                CollectionEnums.parseOptional(CaseOrdering.class, c.ordering(), "queue.ordering_invalid", "Ordem da fila"),
                CollectionEnums.parseOptional(DistributionStrategy.class, c.strategy(), "queue.strategy_invalid",
                        "Estratégia de distribuição"),
                c.maxCasesPerUser(), c.teamIds());
    }

    /** Fila de credor exige o credor no escopo; fila sem credor alcança todos e exige acesso total. */
    private void requireQueueScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("collection_queue");
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    private CollectionQueue load(UUID queueId) {
        CollectionQueue queue = queues.findById(queueId)
                .orElseThrow(() -> ResourceNotFoundException.of("collection_queue", queueId));
        if (queue.getCreditorId() != null) {
            accessScope.requireVisible("collection_queue", queueId, queue.getCreditorId());
        }
        return queue;
    }

    private CollectionQueue loadVisible(UUID queueId) {
        return load(queueId);
    }

    private static Money money(java.math.BigDecimal value) {
        return value == null ? null : Money.of(value);
    }

    private static String criteria(CollectionQueue q) {
        return "creditor=%s;portfolio=%s;overdue=%s-%s;balance=%s-%s;score=%s-%s;teams=%s".formatted(q.getCreditorId(),
                q.getPortfolioId(), q.getMinDaysOverdue(), q.getMaxDaysOverdue(), q.getMinBalance(), q.getMaxBalance(),
                q.getMinScore(), q.getMaxScore(), q.getTeamIds().stream().sorted().toList());
    }
}
