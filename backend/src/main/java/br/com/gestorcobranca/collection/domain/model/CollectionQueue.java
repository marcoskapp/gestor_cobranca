package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Fila de cobrança (F07): critérios que selecionam casos (credor, carteira, faixa de atraso,
 * faixa de saldo e, futuramente, faixa de score), ordem de entrega, estratégia de distribuição e
 * equipes que a atendem. Um caso pertence à primeira fila ativa (menor {@code priority}) cujos
 * critérios ele satisfaz. Critério nulo não restringe.
 */
@Entity
@Table(name = "queue", schema = "collection")
public class CollectionQueue extends TenantScopedEntity {

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "creditor_id")
    private UUID creditorId;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "min_days_overdue")
    private Integer minDaysOverdue;

    @Column(name = "max_days_overdue")
    private Integer maxDaysOverdue;

    @Column(name = "min_balance", precision = 19, scale = 2)
    private Money minBalance;

    @Column(name = "max_balance", precision = 19, scale = 2)
    private Money maxBalance;

    @Column(name = "min_score")
    private Integer minScore;

    @Column(name = "max_score")
    private Integer maxScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "ordering", nullable = false, length = 30)
    private CaseOrdering ordering;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 20)
    private DistributionStrategy strategy;

    @Column(name = "max_cases_per_user")
    private Integer maxCasesPerUser;

    /** Último operador que recebeu caso no rodízio. */
    @Column(name = "round_robin_cursor")
    private UUID roundRobinCursor;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "queue_team", schema = "collection", joinColumns = @JoinColumn(name = "queue_id"))
    @Column(name = "team_id", nullable = false)
    private Set<UUID> teamIds = new HashSet<>();

    protected CollectionQueue() {
    }

    public CollectionQueue(String code, QueueDefinition definition) {
        Guard.require(code != null && code.trim().matches("[A-Za-z0-9_\\-]{2,40}"), "queue.code_invalid",
                "Código da fila deve ter de 2 a 40 letras, números, '_' ou '-'");
        this.code = code.trim().toUpperCase(Locale.ROOT);
        this.active = true;
        define(definition);
    }

    /**
     * Configuração da fila.
     *
     * @param priority        ordem de avaliação no roteamento (menor primeiro)
     * @param maxCasesPerUser capacidade por operador nesta fila (nulo = ilimitada)
     */
    public record QueueDefinition(String name, String description, int priority, UUID creditorId, UUID portfolioId,
                                  Integer minDaysOverdue, Integer maxDaysOverdue, Money minBalance, Money maxBalance,
                                  Integer minScore, Integer maxScore, CaseOrdering ordering,
                                  DistributionStrategy strategy, Integer maxCasesPerUser, Set<UUID> teamIds) {
    }

    public void define(QueueDefinition d) {
        Guard.require(d.name() != null && !d.name().isBlank() && d.name().trim().length() <= 120, "queue.name_invalid",
                "Nome da fila obrigatório (até 120 caracteres)");
        Guard.require(d.priority() >= 0, "queue.priority_invalid", "Prioridade não pode ser negativa");
        Guard.require(d.portfolioId() == null || d.creditorId() != null, "queue.portfolio_requires_creditor",
                "Fila por carteira exige o credor");
        Guard.require(nonNegative(d.minDaysOverdue()) && nonNegative(d.maxDaysOverdue()), "queue.overdue_invalid",
                "Faixa de atraso não pode ser negativa");
        Guard.require(d.minDaysOverdue() == null || d.maxDaysOverdue() == null || d.minDaysOverdue() <= d.maxDaysOverdue(),
                "queue.overdue_invalid", "Atraso mínimo maior que o máximo");
        Guard.require((d.minBalance() == null || !d.minBalance().isNegative())
                        && (d.maxBalance() == null || !d.maxBalance().isNegative()), "queue.balance_invalid",
                "Faixa de saldo não pode ser negativa");
        Guard.require(d.minBalance() == null || d.maxBalance() == null || !d.minBalance().isGreaterThan(d.maxBalance()),
                "queue.balance_invalid", "Saldo mínimo maior que o máximo");
        Guard.require(d.minScore() == null || d.maxScore() == null || d.minScore() <= d.maxScore(), "queue.score_invalid",
                "Score mínimo maior que o máximo");
        Guard.require(d.maxCasesPerUser() == null || d.maxCasesPerUser() > 0, "queue.capacity_invalid",
                "Capacidade por operador deve ser positiva");
        Guard.require(d.description() == null || d.description().length() <= 500, "queue.description_invalid",
                "Descrição até 500 caracteres");
        this.name = d.name().trim();
        this.description = d.description() == null || d.description().isBlank() ? null : d.description().trim();
        this.priority = d.priority();
        this.creditorId = d.creditorId();
        this.portfolioId = d.portfolioId();
        this.minDaysOverdue = d.minDaysOverdue();
        this.maxDaysOverdue = d.maxDaysOverdue();
        this.minBalance = d.minBalance();
        this.maxBalance = d.maxBalance();
        this.minScore = d.minScore();
        this.maxScore = d.maxScore();
        this.ordering = d.ordering() == null ? CaseOrdering.OLDEST_OVERDUE : d.ordering();
        this.strategy = d.strategy() == null ? DistributionStrategy.PULL : d.strategy();
        this.maxCasesPerUser = d.maxCasesPerUser();
        replaceTeams(d.teamIds());
    }

    private void replaceTeams(Collection<UUID> teams) {
        teamIds.clear();
        if (teams != null) {
            teams.stream().filter(java.util.Objects::nonNull).forEach(teamIds::add);
        }
    }

    private static boolean nonNegative(Integer value) {
        return value == null || value >= 0;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    /** Critérios satisfeitos pelo caso na data de referência. */
    public boolean matches(CollectionCase c, LocalDate today) {
        if (creditorId != null && !creditorId.equals(c.getCreditorId())) {
            return false;
        }
        if (portfolioId != null && !portfolioId.equals(c.getPortfolioId())) {
            return false;
        }
        long overdue = c.daysOverdue(today);
        if (minDaysOverdue != null && overdue < minDaysOverdue) {
            return false;
        }
        if (maxDaysOverdue != null && overdue > maxDaysOverdue) {
            return false;
        }
        if (minBalance != null && c.getOpenBalance().isLessThan(minBalance)) {
            return false;
        }
        if (maxBalance != null && c.getOpenBalance().isGreaterThan(maxBalance)) {
            return false;
        }
        if (minScore != null || maxScore != null) {
            // critério de score só seleciona casos já pontuados (módulo scoring)
            if (c.getScore() == null) {
                return false;
            }
            if (minScore != null && c.getScore() < minScore) {
                return false;
            }
            return maxScore == null || c.getScore() <= maxScore;
        }
        return true;
    }

    /** A fila é atendida pelo usuário: sem equipes, qualquer operador; senão, membros das equipes. */
    public boolean isServedBy(Set<UUID> userTeams) {
        return teamIds.isEmpty() || userTeams.stream().anyMatch(teamIds::contains);
    }

    public void advanceRoundRobin(UUID lastAssignedUser) {
        this.roundRobinCursor = lastAssignedUser;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public int getPriority() {
        return priority;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public Integer getMinDaysOverdue() {
        return minDaysOverdue;
    }

    public Integer getMaxDaysOverdue() {
        return maxDaysOverdue;
    }

    public Money getMinBalance() {
        return minBalance;
    }

    public Money getMaxBalance() {
        return maxBalance;
    }

    public Integer getMinScore() {
        return minScore;
    }

    public Integer getMaxScore() {
        return maxScore;
    }

    public CaseOrdering getOrdering() {
        return ordering;
    }

    public DistributionStrategy getStrategy() {
        return strategy;
    }

    public Integer getMaxCasesPerUser() {
        return maxCasesPerUser;
    }

    public UUID getRoundRobinCursor() {
        return roundRobinCursor;
    }

    public Set<UUID> getTeamIds() {
        return Set.copyOf(teamIds);
    }
}
