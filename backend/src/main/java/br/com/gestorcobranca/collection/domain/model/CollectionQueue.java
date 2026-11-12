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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fila de cobrança (F07): critérios que selecionam casos (credor, carteira, faixa de atraso,
 * faixa de saldo, faixa de score, devedor com marcador de vulnerabilidade, devedor com contato novo recebido na carga e
 * tipo de pessoa do devedor), ordem de entrega, estratégia de distribuição e
 * equipes que a atendem. Um caso pertence à primeira fila ativa (menor {@code priority}) cujos
 * critérios ele satisfaz. Critério nulo não restringe.
 * <p>
 * Distribuição automática (distribuicao-por-desempenho.md §4.3): filas com estratégia de distribuição em lote podem
 * disparar o "Distribuir" sozinhas nos horários configurados (fuso de operação, dias úteis), uma vez por horário por
 * dia, até o limite por rodada.
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

    /** Só casos de devedor com marcador de vulnerabilidade (fila própria de atendimento cuidadoso). */
    @Column(name = "vulnerable_only", nullable = false)
    private boolean vulnerableOnly;

    /** Só casos de devedor com contato novo recebido na carga ainda sem tentativa (contatos-recebidos.md §4). */
    @Column(name = "new_contact_only", nullable = false)
    private boolean newContactOnly;

    /** Só casos de devedor do tipo de pessoa (INDIVIDUAL/COMPANY; nulo = qualquer) — cobranca-b2b.md §9. */
    @Column(name = "person_type", length = 20)
    private String personType;

    /** Distribuição automática ligada (só em estratégias de distribuição em lote). */
    @Column(name = "auto_distribution", nullable = false)
    private boolean autoDistribution;

    /** Horários da distribuição automática, {@code HH:mm} separados por vírgula, em ordem. */
    @Column(name = "auto_distribution_times", length = 60)
    private String autoDistributionTimes;

    /** Máximo de casos por rodada automática (1–1000). */
    @Column(name = "auto_distribution_limit")
    private Integer autoDistributionLimit;

    /**
     * Instante até o qual os horários já foram tratados: a última rodada automática ou a última mudança da
     * configuração automática (horários do dia anteriores à mudança não disparam).
     */
    @Column(name = "auto_distribution_last_run_at")
    private Instant autoDistributionLastRunAt;

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
     * @param vulnerableOnly  só casos de devedor com marcador de vulnerabilidade
     * @param newContactOnly  só casos de devedor com contato novo recebido na carga ainda sem tentativa
     * @param personType      só casos de devedor deste tipo de pessoa (INDIVIDUAL/COMPANY; nulo = qualquer)
     */
    public record QueueDefinition(String name, String description, int priority, UUID creditorId, UUID portfolioId,
                                  Integer minDaysOverdue, Integer maxDaysOverdue, Money minBalance, Money maxBalance,
                                  Integer minScore, Integer maxScore, CaseOrdering ordering,
                                  DistributionStrategy strategy, Integer maxCasesPerUser, Set<UUID> teamIds,
                                  boolean vulnerableOnly, boolean newContactOnly, String personType,
                                  AutoDistribution autoDistribution) {
    }

    /**
     * Distribuição automática da fila.
     *
     * @param enabled ligada
     * @param times   horários do dia (fuso de operação), de 1 a {@value #MAX_AUTO_TIMES} quando ligada
     * @param limit   máximo de casos por rodada (1–{@value #MAX_AUTO_LIMIT}), obrigatório quando ligada
     */
    public record AutoDistribution(boolean enabled, List<LocalTime> times, Integer limit) {

        public static final AutoDistribution OFF = new AutoDistribution(false, List.of(), null);

        public AutoDistribution {
            times = times == null ? List.of()
                    : List.copyOf(new TreeSet<>(times.stream().filter(java.util.Objects::nonNull)
                    .map(t -> t.withSecond(0).withNano(0)).toList()));
        }
    }

    public static final int MAX_AUTO_TIMES = 6;
    public static final int MAX_AUTO_LIMIT = 1000;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

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
        Guard.require(d.strategy() != DistributionStrategy.TIERED || d.maxCasesPerUser() != null,
                "queue.tiered_capacity_required",
                "Distribuição por nível de desempenho exige a capacidade por operador (base do multiplicador por nível)");
        AutoDistribution auto = d.autoDistribution() == null ? AutoDistribution.OFF : d.autoDistribution();
        Guard.require(auto.limit() == null || (auto.limit() >= 1 && auto.limit() <= MAX_AUTO_LIMIT),
                "queue.auto_distribution_limit_invalid", "Limite por rodada entre 1 e " + MAX_AUTO_LIMIT + " casos");
        Guard.require(auto.times().size() <= MAX_AUTO_TIMES, "queue.auto_distribution_times_invalid",
                "Até " + MAX_AUTO_TIMES + " horários de distribuição automática");
        if (auto.enabled()) {
            DistributionStrategy strategy = d.strategy() == null ? DistributionStrategy.PULL : d.strategy();
            Guard.require(strategy != DistributionStrategy.PULL, "queue.auto_distribution_pull",
                    "Fila sob demanda não tem distribuição automática: escolha rodízio, menor carga ou por nível");
            Guard.require(!auto.times().isEmpty(), "queue.auto_distribution_times_invalid",
                    "Informe ao menos um horário de distribuição automática");
            Guard.require(auto.limit() != null, "queue.auto_distribution_limit_invalid",
                    "Informe o limite de casos por rodada automática");
        }
        Guard.require(d.description() == null || d.description().length() <= 500, "queue.description_invalid",
                "Descrição até 500 caracteres");
        String person = d.personType() == null || d.personType().isBlank() ? null
                : d.personType().trim().toUpperCase(Locale.ROOT);
        Guard.require(person == null || CollectionCase.PERSON_TYPES.contains(person), "queue.person_type_invalid",
                "Tipo de pessoa: pessoa física ou jurídica");
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
        this.vulnerableOnly = d.vulnerableOnly();
        this.newContactOnly = d.newContactOnly();
        this.personType = person;
        this.autoDistribution = auto.enabled();
        this.autoDistributionTimes = auto.times().isEmpty() ? null
                : auto.times().stream().map(HH_MM::format).collect(Collectors.joining(","));
        this.autoDistributionLimit = auto.limit();
        replaceTeams(d.teamIds());
    }

    /** Converte {@code HH:mm} da API; inválido → 422 {@code queue.auto_distribution_times_invalid}. */
    public static List<LocalTime> parseTimes(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        try {
            return values.stream().filter(v -> v != null && !v.isBlank())
                    .map(v -> LocalTime.parse(v.trim(), HH_MM)).toList();
        } catch (DateTimeParseException e) {
            throw new br.com.gestorcobranca.shared.domain.BusinessRuleException("queue.auto_distribution_times_invalid",
                    "Horário inválido: use HH:mm (ex.: 08:00)");
        }
    }

    public AutoDistribution autoDistribution() {
        List<LocalTime> times = autoDistributionTimes == null ? List.of()
                : Arrays.stream(autoDistributionTimes.split(",")).map(t -> LocalTime.parse(t, HH_MM)).toList();
        return new AutoDistribution(autoDistribution, times, autoDistributionLimit);
    }

    /**
     * Horário de distribuição automática vencido e ainda não tratado: o último horário do dia já alcançado, se a
     * última rodada (ou mudança de configuração) foi antes dele. Vários horários perdidos (instância parada) geram uma
     * única rodada.
     *
     * @param now instante corrente no fuso de operação
     */
    public Optional<LocalTime> dueAutoDistribution(ZonedDateTime now) {
        if (!active || !autoDistribution || strategy == DistributionStrategy.PULL) {
            return Optional.empty();
        }
        LocalTime current = now.toLocalTime();
        Optional<LocalTime> slot = autoDistribution().times().stream().filter(t -> !t.isAfter(current))
                .reduce((a, b) -> b);
        if (slot.isEmpty()) {
            return Optional.empty();
        }
        Instant slotAt = now.toLocalDate().atTime(slot.get()).atZone(now.getZone()).toInstant();
        return autoDistributionLastRunAt != null && !autoDistributionLastRunAt.isBefore(slotAt) ? Optional.empty() : slot;
    }

    /** Registra a rodada automática (ou a mudança da configuração automática) no instante informado. */
    public void autoDistributionHandled(Instant at) {
        this.autoDistributionLastRunAt = at;
    }

    public Instant getAutoDistributionLastRunAt() {
        return autoDistributionLastRunAt;
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

    /**
     * Critérios satisfeitos pelo caso.
     *
     * @param today data de referência do atraso (fuso de operação)
     * @param now   instante de referência da janela de novidade do contato novo
     */
    public boolean matches(CollectionCase c, LocalDate today, Instant now) {
        if (creditorId != null && !creditorId.equals(c.getCreditorId())) {
            return false;
        }
        if (portfolioId != null && !portfolioId.equals(c.getPortfolioId())) {
            return false;
        }
        if (vulnerableOnly && !c.isVulnerable()) {
            return false;
        }
        if (newContactOnly && !c.hasNewContact(now)) {
            return false;
        }
        // tipo de pessoa exigido só seleciona casos com o tipo conhecido
        if (personType != null && !personType.equals(c.getDebtorPersonType())) {
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

    public boolean isVulnerableOnly() {
        return vulnerableOnly;
    }

    public boolean isNewContactOnly() {
        return newContactOnly;
    }

    public String getPersonType() {
        return personType;
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
