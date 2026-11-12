package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.application.port.out.CaseSelector;
import br.com.gestorcobranca.collection.domain.model.CaseOrdering;
import br.com.gestorcobranca.collection.domain.model.PotentialBand;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CaseSelector} em SQL nativo: a ordem de entrega é dinâmica e {@code SKIP LOCKED} não é
 * expresso em JPQL. Executa na conexão da transação JPA corrente (o tenant já está definido e o
 * RLS restringe as linhas). Atende ao índice parcial {@code ix_case_queue_available}.
 */
@Component
class JdbcCaseSelector implements CaseSelector {

    /** Condições de disponível sobre o alias {@code c}. */
    private static final String AVAILABLE_CONDITIONS = """
                c.queue_id = :queueId
               and c.status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE')
               and c.eligible
               and c.assignee_user_id is null
               and c.owner_type = 'QUEUE'
               and (c.next_action_at is null or c.next_action_at <= :now)
            """;

    private static final String AVAILABLE = "select c.id, c.creditor_id from collection.collection_case c where "
            + AVAILABLE_CONDITIONS;

    /**
     * Disponíveis da fila com valor esperado e faixa (tercis por {@code ntile(3)}, a mesma regra de
     * {@code CaseDistributor#tercile}): probabilidade = score ÷ 1000; sem score, a média dos disponíveis com score; sem
     * nenhum score, 1 (o valor esperado vira o saldo). As faixas são calculadas sobre a fila inteira, antes do filtro de
     * credores do operador. As condições de disponível se repetem na consulta externa: com {@code FOR UPDATE}, a linha
     * relida depois de um concorrente precisa continuar disponível.
     */
    private static final String RANKED = """
            with available as (
                select c.id, c.score, c.eligible_balance
                  from collection.collection_case c
                 where """ + AVAILABLE_CONDITIONS + """
            ), stats as (
                -- greatest/least ignoram nulos: o score nulo precisa ficar de fora explicitamente
                select coalesce(avg(least(greatest(score, 0), 1000)) filter (where score is not null) / 1000.0, 1.0)
                           as mean_p
                  from available
            ), ranked as (
                select a.id, a.score, a.eligible_balance,
                       case when a.score is null then s.mean_p
                            else least(greatest(a.score, 0), 1000) / 1000.0 end * a.eligible_balance as expected_value
                  from available a cross join stats s
            ), banded as (
                select r.id, r.score, r.eligible_balance, r.expected_value,
                       ntile(3) over (order by r.expected_value desc, r.id) as tercile
                  from ranked r
            )
            select c.id, c.creditor_id, b.score, b.eligible_balance, b.expected_value, b.tercile
              from collection.collection_case c
              join banded b on b.id = c.id
             where """ + AVAILABLE_CONDITIONS;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCaseSelector(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> lockNextAvailable(UUID queueId, CaseOrdering ordering, Collection<UUID> allowedCreditorIds,
                                            Instant now) {
        if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("queueId", queueId).addValue("now", Timestamp.from(now));
        StringBuilder sql = new StringBuilder(AVAILABLE);
        if (allowedCreditorIds != null) {
            sql.append(" and c.creditor_id in (:creditors)");
            params.addValue("creditors", allowedCreditorIds);
        }
        sql.append(orderBy(ordering)).append(" limit 1 for update of c skip locked");
        return jdbc.query(sql.toString(), params, (rs, i) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    @Override
    public List<Candidate> lockAvailable(UUID queueId, CaseOrdering ordering, Instant now, int limit) {
        return available(queueId, ordering, now, limit, true);
    }

    @Override
    public List<Candidate> listAvailable(UUID queueId, CaseOrdering ordering, Instant now, int limit) {
        return available(queueId, ordering, now, limit, false);
    }

    private List<Candidate> available(UUID queueId, CaseOrdering ordering, Instant now, int limit, boolean lock) {
        MapSqlParameterSource params = new MapSqlParameterSource("queueId", queueId).addValue("now", Timestamp.from(now))
                .addValue("limit", limit);
        String sql = AVAILABLE + orderBy(ordering) + " limit :limit" + (lock ? " for update of c skip locked" : "");
        return jdbc.query(sql, params,
                (rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("creditor_id", UUID.class)));
    }

    @Override
    public List<RankedCandidate> lockAvailableByPotential(UUID queueId, Instant now, int limit) {
        return ranked(queueId, now, limit, true);
    }

    @Override
    public List<RankedCandidate> listAvailableByPotential(UUID queueId, Instant now, int limit) {
        return ranked(queueId, now, limit, false);
    }

    private List<RankedCandidate> ranked(UUID queueId, Instant now, int limit, boolean lock) {
        MapSqlParameterSource params = new MapSqlParameterSource("queueId", queueId).addValue("now", Timestamp.from(now))
                .addValue("limit", limit);
        String sql = RANKED + " order by b.expected_value desc, c.id limit :limit"
                + (lock ? " for update of c skip locked" : "");
        return jdbc.query(sql, params, (rs, i) -> rankedRow(rs));
    }

    @Override
    public Optional<RankedCandidate> lockNextByPotential(UUID queueId, List<PotentialBand> bands,
                                                         Collection<UUID> allowedCreditorIds, Instant now) {
        if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("queueId", queueId).addValue("now", Timestamp.from(now));
        StringBuilder sql = new StringBuilder(RANKED);
        if (allowedCreditorIds != null) {
            sql.append(" and c.creditor_id in (:creditors)");
            params.addValue("creditors", allowedCreditorIds);
        }
        // faixas na ordem de preferência; dentro da faixa, retornos da fila vencidos e depois o maior valor esperado
        sql.append(" order by case b.tercile");
        for (int i = 0; i < bands.size(); i++) {
            sql.append(" when ").append(tercileOf(bands.get(i))).append(" then ").append(i);
        }
        sql.append(" else ").append(bands.size()).append(" end, (c.next_action_at is null), c.next_action_at,")
                .append(" b.expected_value desc, c.id limit 1 for update of c skip locked");
        return jdbc.query(sql.toString(), params, (rs, i) -> rankedRow(rs)).stream().findFirst();
    }

    @Override
    public Optional<UUID> lockOwnDue(UUID userId, Collection<UUID> allowedCreditorIds, Instant now) {
        if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("now", Timestamp.from(now));
        StringBuilder sql = new StringBuilder("""
                select c.id from collection.collection_case c
                 where c.assignee_user_id = :userId
                   and c.status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE')
                   and c.eligible
                   and c.next_action_at is not null and c.next_action_at <= :now
                """);
        if (allowedCreditorIds != null) {
            sql.append(" and c.creditor_id in (:creditors)");
            params.addValue("creditors", allowedCreditorIds);
        }
        sql.append(" order by c.next_action_at, c.id limit 1 for update of c skip locked");
        return jdbc.query(sql.toString(), params, (rs, i) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    private static RankedCandidate rankedRow(ResultSet rs) throws SQLException {
        Number score = (Number) rs.getObject("score");
        return new RankedCandidate(rs.getObject("id", UUID.class), rs.getObject("creditor_id", UUID.class),
                score == null ? null : score.intValue(), rs.getBigDecimal("eligible_balance"),
                bandOf(rs.getInt("tercile")));
    }

    private static int tercileOf(PotentialBand band) {
        return switch (band) {
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
            case DEVELOPMENT -> throw new IllegalArgumentException("Faixa de desenvolvimento não é um tercil");
        };
    }

    private static PotentialBand bandOf(int tercile) {
        return switch (tercile) {
            case 1 -> PotentialBand.HIGH;
            case 2 -> PotentialBand.MEDIUM;
            default -> PotentialBand.LOW;
        };
    }

    /** Retornos da fila já vencidos primeiro; depois a ordem configurada; desempate estável pelo id. */
    private static String orderBy(CaseOrdering ordering) {
        String criteria = switch (ordering == null ? CaseOrdering.OLDEST_OVERDUE : ordering) {
            case OLDEST_OVERDUE -> "c.oldest_due_date asc nulls last";
            case HIGHEST_BALANCE -> "c.eligible_balance desc";
            case LOWEST_BALANCE -> "c.eligible_balance asc";
            case LEAST_RECENTLY_WORKED -> "c.last_interaction_at asc nulls first";
            case HIGHEST_SCORE -> "c.score desc nulls last";
        };
        return " order by (c.next_action_at is null), c.next_action_at, " + criteria + ", c.id";
    }
}
