package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.application.port.out.ContactFactStore;
import br.com.gestorcobranca.scoring.domain.model.ContactChannel;
import br.com.gestorcobranca.scoring.domain.model.ContactFact;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow.SlotCount;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.RateBase;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link ContactFactStore} em SQL; RLS restringe ao tenant da transação. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcContactFactStore implements ContactFactStore {

    private static final TypeReference<List<SlotJson>> SLOTS = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    JdbcContactFactStore(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Faixa horária serializada no perfil: tentativas, sucessos e a última tentativa. */
    record SlotJson(int attempts, int successes, Instant lastAttemptAt) {
    }

    @Override
    public boolean record(ContactFact f, String contactOrigin) {
        return jdbc.update("""
                insert into scoring.contact_attempt (tenant_id, source_event_id, kind, debtor_id, creditor_id,
                    portfolio_id, case_id, contact_id, channel, direction, success, local_hour, local_weekday,
                    person_type, occurred_at, contact_origin)
                values (shared.current_tenant_id(), :event, :kind, :debtor, :creditor, :portfolio, :caseId, :contact,
                        :channel, :direction, :success, :hour, :weekday, :personType, :occurredAt, :origin)
                on conflict do nothing
                """, new MapSqlParameterSource("event", f.sourceEventId()).addValue("kind", f.kind().name())
                .addValue("debtor", f.debtorId()).addValue("creditor", f.creditorId(), Types.OTHER)
                .addValue("portfolio", f.portfolioId(), Types.OTHER).addValue("caseId", f.caseId(), Types.OTHER)
                .addValue("contact", f.contactId(), Types.OTHER).addValue("channel", f.channel().name())
                .addValue("direction", f.direction().name()).addValue("success", f.success())
                .addValue("hour", f.localHour()).addValue("weekday", f.localWeekday())
                .addValue("personType", f.personType(), Types.VARCHAR)
                .addValue("occurredAt", Timestamp.from(f.occurredAt()))
                .addValue("origin", contactOrigin, Types.VARCHAR)) > 0;
    }

    @Override
    public boolean markLatestAttemptAnswered(UUID debtorId, UUID creditorId, UUID contactId, ContactChannel channel,
                                             Instant from, Instant to, UUID responseEventId) {
        MapSqlParameterSource p = new MapSqlParameterSource("debtor", debtorId).addValue("channel", channel.name())
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("response", responseEventId);
        String creditor = creditorId == null ? "creditor_id is null" : "creditor_id = :creditor";
        if (creditorId != null) {
            p.addValue("creditor", creditorId);
        }
        // mesma série do perfil e do painel (credor, contato, canal): resposta sem contato só responde tentativa sem
        // contato — senão viraria sucesso de uma série e "iniciada" em outra
        String contact = " and contact_id is null";
        if (contactId != null) {
            contact = " and contact_id = :contact";
            p.addValue("contact", contactId);
        }
        // a mais recente da janela, com ou sem sucesso: se já foi atendida ou respondida, nada é marcado
        return jdbc.update("""
                update scoring.contact_attempt a
                   set success = true, responded_by_event_id = :response
                  from (select tenant_id, source_event_id from scoring.contact_attempt
                         where debtor_id = :debtor and channel = :channel and kind = 'ATTEMPT' and %s%s
                           and occurred_at between :from and :to
                         order by occurred_at desc, source_event_id desc
                         limit 1
                         for update) latest
                 where a.tenant_id = latest.tenant_id and a.source_event_id = latest.source_event_id
                   and not a.success
                """.formatted(creditor, contact), p) > 0;
    }

    @Override
    public List<ContactFact> factsOfDebtor(UUID debtorId) {
        return jdbc.query("""
                select source_event_id, kind, debtor_id, creditor_id, portfolio_id, case_id, contact_id, channel,
                       direction, success, local_hour, local_weekday, person_type, occurred_at
                  from scoring.contact_attempt
                 where debtor_id = :debtor
                """, new MapSqlParameterSource("debtor", debtorId), (rs, i) -> new ContactFact(
                rs.getObject("source_event_id", UUID.class), ContactFact.Kind.valueOf(rs.getString("kind")),
                rs.getObject("debtor_id", UUID.class), rs.getObject("creditor_id", UUID.class),
                rs.getObject("portfolio_id", UUID.class), rs.getObject("case_id", UUID.class),
                rs.getObject("contact_id", UUID.class), ContactChannel.valueOf(rs.getString("channel")),
                ContactFact.Direction.valueOf(rs.getString("direction")), rs.getBoolean("success"),
                rs.getInt("local_hour"), rs.getInt("local_weekday"), rs.getString("person_type"),
                rs.getTimestamp("occurred_at").toInstant()));
    }

    @Override
    public void replaceProfiles(UUID debtorId, Collection<ContactProfileRow> rows, Instant updatedAt) {
        jdbc.update("delete from scoring.contact_profile where debtor_id = :debtor",
                new MapSqlParameterSource("debtor", debtorId));
        if (rows.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = rows.stream().map(r -> new MapSqlParameterSource("debtor", r.debtorId())
                .addValue("creditor", r.creditorId(), Types.OTHER).addValue("contact", r.contactId(), Types.OTHER)
                .addValue("channel", r.channel().name()).addValue("attempts", r.attempts())
                .addValue("successes", r.successes()).addValue("initiated", r.debtorInitiated())
                .addValue("reads", r.reads()).addValue("first", r.attemptsToFirstSuccess(), Types.INTEGER)
                .addValue("lastSuccess", timestamp(r.lastSuccessAt()), Types.TIMESTAMP)
                .addValue("lastAttempt", timestamp(r.lastAttemptAt()), Types.TIMESTAMP)
                .addValue("slots", json.toJson(r.slots().stream()
                        .map(s -> new SlotJson(s.attempts(), s.successes(), s.lastAttemptAt())).toList()))
                .addValue("updatedAt", Timestamp.from(updatedAt))).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into scoring.contact_profile (tenant_id, debtor_id, creditor_id, contact_id, channel, attempts,
                    successes, debtor_initiated, reads, attempts_to_first_success, last_success_at, last_attempt_at,
                    slots, updated_at)
                values (shared.current_tenant_id(), :debtor, :creditor, :contact, :channel, :attempts, :successes,
                        :initiated, :reads, :first, :lastSuccess, :lastAttempt, cast(:slots as jsonb), :updatedAt)
                """, batch);
    }

    @Override
    public List<ContactProfileRow> profilesOfDebtor(UUID debtorId) {
        return jdbc.query("""
                select debtor_id, creditor_id, contact_id, channel, attempts, successes, debtor_initiated, reads,
                       attempts_to_first_success, last_success_at, last_attempt_at, slots
                  from scoring.contact_profile
                 where debtor_id = :debtor
                """, new MapSqlParameterSource("debtor", debtorId), (rs, i) -> new ContactProfileRow(
                rs.getObject("debtor_id", UUID.class), rs.getObject("creditor_id", UUID.class),
                rs.getObject("contact_id", UUID.class), ContactChannel.valueOf(rs.getString("channel")),
                rs.getInt("attempts"), rs.getInt("successes"), rs.getInt("debtor_initiated"), rs.getInt("reads"),
                (Integer) rs.getObject("attempts_to_first_success"), instant(rs, "last_success_at"),
                instant(rs, "last_attempt_at"), json.fromJson(rs.getString("slots"), SLOTS).stream()
                .map(s -> new SlotCount(s.attempts(), s.successes(), s.lastAttemptAt())).toList()));
    }

    // ---------------------------------------------------------------- base das taxas (materializada)

    @Override
    public int refreshRateBase(Instant since, Instant refreshedAt) {
        MapSqlParameterSource p = new MapSqlParameterSource("since", Timestamp.from(since))
                .addValue("refreshedAt", Timestamp.from(refreshedAt));
        jdbc.update("delete from scoring.contact_rate_base", p);
        // por carteira e canal, e por assessoria e canal (inclui fatos sem carteira)
        int rows = jdbc.update("""
                insert into scoring.contact_rate_base (tenant_id, portfolio_id, channel, attempts, successes,
                    median_attempts_to_first_success, window_from, refreshed_at)
                select shared.current_tenant_id(), portfolio_id, channel, count(*), count(*) filter (where success),
                       null, :since, :refreshedAt
                  from scoring.contact_attempt
                 where kind = 'ATTEMPT' and occurred_at >= :since
                 group by grouping sets ((portfolio_id, channel), (channel))
                having grouping(portfolio_id) = 1 or portfolio_id is not null
                """, p);
        // por carteira (todos os canais): totais e a mediana de tentativas até o 1º sucesso de cada série
        rows += jdbc.update("""
                insert into scoring.contact_rate_base (tenant_id, portfolio_id, channel, attempts, successes,
                    median_attempts_to_first_success, window_from, refreshed_at)
                with facts as (
                    select portfolio_id, debtor_id, contact_id, channel, success,
                           row_number() over (partition by portfolio_id, debtor_id, contact_id, channel
                                              order by occurred_at, source_event_id) as rn
                      from scoring.contact_attempt
                     where kind = 'ATTEMPT' and occurred_at >= :since and portfolio_id is not null),
                series as (
                    select portfolio_id, min(rn) filter (where success) as position
                      from facts group by portfolio_id, debtor_id, contact_id, channel),
                medians as (
                    select portfolio_id, percentile_cont(0.5) within group (order by position) as median
                      from series where position is not null group by portfolio_id),
                totals as (
                    select portfolio_id, count(*) as attempts, count(*) filter (where success) as successes
                      from facts group by portfolio_id)
                select shared.current_tenant_id(), t.portfolio_id, null, t.attempts, t.successes, m.median, :since,
                       :refreshedAt
                  from totals t left join medians m on m.portfolio_id = t.portfolio_id
                """, p);
        return rows;
    }

    @Override
    public Map<ContactChannel, RateBase> rates(UUID portfolioId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "portfolio_id is null";
        if (portfolioId != null) {
            where = "portfolio_id = :portfolio";
            p.addValue("portfolio", portfolioId);
        }
        Map<ContactChannel, RateBase> out = new EnumMap<>(ContactChannel.class);
        jdbc.query("select channel, attempts, successes from scoring.contact_rate_base where channel is not null and "
                + where, p, rs -> {
            out.put(ContactChannel.valueOf(rs.getString("channel")),
                    new RateBase(rs.getLong("attempts"), rs.getLong("successes")));
        });
        return out;
    }

    @Override
    public Double medianAttemptsToFirstSuccess(UUID portfolioId) {
        return jdbc.query("""
                select median_attempts_to_first_success from scoring.contact_rate_base
                 where portfolio_id = :portfolio and channel is null
                """, new MapSqlParameterSource("portfolio", portfolioId), rs -> {
            if (!rs.next()) {
                return null;
            }
            double median = rs.getDouble(1);
            return rs.wasNull() ? null : median;
        });
    }

    // ---------------------------------------------------------------- retenção e anonimização

    @Override
    public List<UUID> debtorsWithFactsBefore(Instant before, int limit) {
        return jdbc.queryForList("""
                select distinct debtor_id from scoring.contact_attempt
                 where occurred_at < :before
                 order by debtor_id
                 limit :limit
                """, new MapSqlParameterSource("before", Timestamp.from(before)).addValue("limit", limit), UUID.class);
    }

    @Override
    public int deleteFactsOfDebtorBefore(UUID debtorId, Instant before) {
        return jdbc.update("delete from scoring.contact_attempt where debtor_id = :debtor and occurred_at < :before",
                new MapSqlParameterSource("debtor", debtorId).addValue("before", Timestamp.from(before)));
    }

    @Override
    public Removal deleteByDebtor(UUID debtorId, ZoneId zone) {
        MapSqlParameterSource p = new MapSqlParameterSource("debtor", debtorId).addValue("zone", zone.getId());
        int facts = jdbc.update("delete from scoring.contact_attempt where debtor_id = :debtor", p);
        int profiles = jdbc.update("delete from scoring.contact_profile where debtor_id = :debtor", p);
        int outcomes = jdbc.update("""
                update scoring.agreement_contact_outcome
                   set debtor_id = null, case_id = null, agreement_id = null,
                """ + NEUTRALIZED_INSTANTS + """
                 where debtor_id = :debtor
                """, p);
        return new Removal(facts, profiles, outcomes);
    }

    @Override
    public Optional<OutcomeRow> findOutcome(UUID agreementId) {
        return jdbc.query("""
                select id, agreement_id, case_id, debtor_id, creditor_id, portfolio_id, person_type, agreed_at,
                       attempts_before, effective_before, last_effective_channel, days_first_effective, final_status,
                       status_at, updated_at
                  from scoring.agreement_contact_outcome
                 where agreement_id = :agreement
                 for update
                """, new MapSqlParameterSource("agreement", agreementId), (rs, i) -> {
            Instant agreedAt = rs.getTimestamp("agreed_at").toInstant();
            Integer days = (Integer) rs.getObject("days_first_effective");
            String channel = rs.getString("last_effective_channel");
            Episode episode = new Episode(rs.getInt("attempts_before"), rs.getInt("effective_before"),
                    days == null ? null : agreedAt.minus(java.time.Duration.ofDays(days)),
                    channel == null ? null : ContactChannel.valueOf(channel));
            return new OutcomeRow(rs.getObject("id", UUID.class), rs.getObject("agreement_id", UUID.class),
                    rs.getObject("case_id", UUID.class), rs.getObject("debtor_id", UUID.class),
                    rs.getObject("creditor_id", UUID.class), rs.getObject("portfolio_id", UUID.class),
                    rs.getString("person_type"), agreedAt, episode, rs.getString("final_status"),
                    rs.getTimestamp("status_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        }).stream().findFirst();
    }

    @Override
    public Episode episode(UUID debtorId, UUID creditorId, UUID agreementId, Instant agreedAt) {
        return jdbc.queryForObject("""
                select count(*) filter (where kind = 'ATTEMPT') as attempts,
                       count(*) filter (where success) as effective,
                       min(occurred_at) filter (where success) as first_effective,
                       (array_agg(channel order by occurred_at desc) filter (where success))[1] as last_channel
                  from scoring.contact_attempt
                 where debtor_id = :debtor and creditor_id = :creditor and occurred_at <= :agreedAt
                   and occurred_at > coalesce((
                       select max(o.agreed_at) from scoring.agreement_contact_outcome o
                        where o.debtor_id = :debtor and o.creditor_id = :creditor and o.agreed_at < :agreedAt
                          and o.agreement_id is distinct from :agreement), '-infinity'::timestamptz)
                """, new MapSqlParameterSource("debtor", debtorId).addValue("creditor", creditorId)
                .addValue("agreement", agreementId).addValue("agreedAt", Timestamp.from(agreedAt)), (rs, i) -> {
            String channel = rs.getString("last_channel");
            return new Episode(rs.getInt("attempts"), rs.getInt("effective"), instant(rs, "first_effective"),
                    channel == null ? null : ContactChannel.valueOf(channel));
        });
    }

    @Override
    public void insertOutcome(OutcomeRow r) {
        jdbc.update("""
                insert into scoring.agreement_contact_outcome (tenant_id, id, agreement_id, case_id, debtor_id,
                    creditor_id, portfolio_id, person_type, agreed_at, attempts_before, effective_before,
                    last_effective_channel, days_first_effective, final_status, status_at, updated_at)
                values (shared.current_tenant_id(), :id, :agreement, :caseId, :debtor, :creditor, :portfolio,
                        :personType, :agreedAt, :attempts, :effective, :channel, :days, :status, :statusAt, :updatedAt)
                on conflict do nothing
                """, outcomeParams(r));
    }

    @Override
    public void updateOutcome(OutcomeRow r) {
        jdbc.update("""
                update scoring.agreement_contact_outcome
                   set attempts_before = :attempts, effective_before = :effective, last_effective_channel = :channel,
                       days_first_effective = :days, final_status = :status, status_at = :statusAt,
                       case_id = coalesce(case_id, :caseId), portfolio_id = coalesce(portfolio_id, :portfolio),
                       updated_at = :updatedAt
                 where id = :id and debtor_id is not null
                """, outcomeParams(r));
    }

    @Override
    public int neutralizeOutcomesBefore(Instant before, int limit, ZoneId zone) {
        return jdbc.update("""
                update scoring.agreement_contact_outcome o
                   set debtor_id = null, case_id = null, agreement_id = null,
                """ + NEUTRALIZED_INSTANTS + """
                 where o.id in (select id from scoring.agreement_contact_outcome
                                 where agreed_at < :before and final_status <> 'ACTIVE'
                                   and (debtor_id is not null or agreement_id is not null or case_id is not null)
                                 order by agreed_at, id
                                 limit :limit)
                """, new MapSqlParameterSource("before", Timestamp.from(before)).addValue("limit", limit)
                .addValue("zone", zone.getId()));
    }

    /**
     * Instantes do desfecho sem identificação truncados para o dia no fuso informado: a linha não pode ser religada ao
     * acordo pelo instante exato (os dias até o acordo e o filtro por período continuam valendo).
     */
    private static final String NEUTRALIZED_INSTANTS = """
                       agreed_at = date_trunc('day', agreed_at, :zone),
                       status_at = date_trunc('day', status_at, :zone),
                       updated_at = date_trunc('day', now(), :zone)
            """;

    private static MapSqlParameterSource outcomeParams(OutcomeRow r) {
        Episode e = r.episode();
        Integer days = e.firstEffectiveAt() == null ? null
                : (int) java.time.Duration.between(e.firstEffectiveAt(), r.agreedAt()).toDays();
        return new MapSqlParameterSource("id", r.id()).addValue("agreement", r.agreementId(), Types.OTHER)
                .addValue("caseId", r.caseId(), Types.OTHER).addValue("debtor", r.debtorId(), Types.OTHER)
                .addValue("creditor", r.creditorId()).addValue("portfolio", r.portfolioId(), Types.OTHER)
                .addValue("personType", r.personType(), Types.VARCHAR).addValue("agreedAt", Timestamp.from(r.agreedAt()))
                .addValue("attempts", e.attempts()).addValue("effective", e.effective())
                .addValue("channel", e.lastEffectiveChannel() == null ? null : e.lastEffectiveChannel().name(),
                        Types.VARCHAR)
                .addValue("days", days, Types.INTEGER).addValue("status", r.finalStatus())
                .addValue("statusAt", Timestamp.from(r.statusAt())).addValue("updatedAt", Timestamp.from(r.updatedAt()));
    }

    // ---------------------------------------------------------------- painel da carteira

    @Override
    public List<HeatCell> heatmap(InsightFilter filter) {
        Where w = factWhere(filter, "kind = 'ATTEMPT' and local_hour >= 6");
        return jdbc.query("select local_weekday, 6 + ((local_hour - 6) / 3) * 3 as start_hour, count(*) as attempts, "
                        + "count(*) filter (where success) as successes from scoring.contact_attempt where " + w.sql
                        + " group by 1, 2 order by 1, 2", w.params,
                (rs, i) -> new HeatCell(rs.getInt("local_weekday"), rs.getInt("start_hour"), rs.getLong("attempts"),
                        rs.getLong("successes")));
    }

    @Override
    public List<ChannelCount> channels(InsightFilter filter) {
        Where w = factWhere(filter, "true");
        // iniciada pelo devedor: a mesma regra do ContactProfileCalculator (mensagem que responde a uma tentativa ou
        // continua a conversa da série nas últimas 72 h não conta)
        return jdbc.query("select channel, count(*) filter (where kind = 'ATTEMPT') as attempts, "
                        + "count(*) filter (where kind = 'ATTEMPT' and success) as successes, "
                        + "count(*) filter (where initiated) as initiated, "
                        + "count(*) filter (where kind = 'READ') as reads from ("
                        + " select f.channel, f.kind, f.success, f.kind = 'RESPONSE' and " + DEBTOR_INITIATED
                        + " as initiated from scoring.contact_attempt f where " + w.sql + ") x"
                        + " group by channel order by channel",
                w.params.addValue("replyWindowSeconds", ContactProfileCalculator.REPLY_WINDOW.toSeconds()),
                (rs, i) -> new ChannelCount(ContactChannel.valueOf(rs.getString("channel")), rs.getLong("attempts"),
                        rs.getLong("successes"), rs.getLong("initiated"), rs.getLong("reads")));
    }

    @Override
    public List<FirstSuccessCount> firstSuccess(InsightFilter filter) {
        Where w = factWhere(filter, "kind = 'ATTEMPT'");
        return jdbc.query("select position, count(*) as series from ("
                        + " select min(rn) filter (where success) as position from ("
                        + "  select debtor_id, contact_id, channel, success, row_number() over (partition by debtor_id, "
                        + "  contact_id, channel order by occurred_at, source_event_id) as rn"
                        + "  from scoring.contact_attempt where " + w.sql + ") s"
                        + " group by debtor_id, contact_id, channel) t group by position order by position", w.params,
                (rs, i) -> {
                    long position = rs.getLong("position");
                    return new FirstSuccessCount(rs.wasNull() ? null : (int) position, rs.getLong("series"));
                });
    }

    @Override
    public List<PersonTypeCount> attemptsByPersonType(InsightFilter filter) {
        Where w = factWhere(filter, "kind = 'ATTEMPT' and person_type is not null");
        return jdbc.query("select person_type, count(*) as attempts, count(*) filter (where success) as successes "
                        + "from scoring.contact_attempt where " + w.sql + " group by person_type order by person_type",
                w.params, (rs, i) -> new PersonTypeCount(rs.getString("person_type"), rs.getLong("attempts"),
                        rs.getLong("successes")));
    }

    @Override
    public List<ContactOriginCount> attemptsByContactOrigin(InsightFilter filter) {
        Where w = factWhere(filter, "kind = 'ATTEMPT' and contact_origin is not null");
        return jdbc.query("select contact_origin, count(*) as attempts, count(*) filter (where success) as successes "
                        + "from scoring.contact_attempt where " + w.sql + " group by contact_origin order by contact_origin",
                w.params, (rs, i) -> new ContactOriginCount(rs.getString("contact_origin"), rs.getLong("attempts"),
                        rs.getLong("successes")));
    }

    @Override
    public List<OutcomeCount> outcomes(InsightFilter filter) {
        StringBuilder sql = new StringBuilder("""
                select attempts_before, days_first_effective, final_status, person_type, count(*) as agreements
                  from scoring.agreement_contact_outcome
                 where agreed_at >= :from and agreed_at < :to""");
        MapSqlParameterSource p = scopeParams(filter, sql);
        sql.append(" group by 1, 2, 3, 4");
        return jdbc.query(sql.toString(), p, (rs, i) -> new OutcomeCount(rs.getInt("attempts_before"),
                (Integer) rs.getObject("days_first_effective"), rs.getString("final_status"),
                rs.getString("person_type"), rs.getLong("agreements")));
    }

    /** Resposta {@code f} iniciada pelo devedor (ver {@code ContactProfileCalculator#row}). */
    private static final String DEBTOR_INITIATED = """
            (f.channel not in ('WHATSAPP', 'SMS', 'EMAIL') or not exists (
                select 1 from scoring.contact_attempt p
                 where p.debtor_id = f.debtor_id and p.channel = f.channel and p.kind in ('ATTEMPT', 'RESPONSE')
                   and p.creditor_id is not distinct from f.creditor_id
                   and p.contact_id is not distinct from f.contact_id
                   and p.occurred_at >= f.occurred_at - make_interval(secs => :replyWindowSeconds)
                   and (p.occurred_at, p.source_event_id) < (f.occurred_at, f.source_event_id)))""";

    private record Where(String sql, MapSqlParameterSource params) {
    }

    private static Where factWhere(InsightFilter filter, String base) {
        StringBuilder sql = new StringBuilder(base).append(" and occurred_at >= :from and occurred_at < :to");
        MapSqlParameterSource p = scopeParams(filter, sql);
        return new Where(sql.toString(), p);
    }

    /** Período, credores permitidos, carteira e tipo de pessoa (colunas com o mesmo nome nas duas tabelas). */
    private static MapSqlParameterSource scopeParams(InsightFilter filter, StringBuilder sql) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", Timestamp.from(filter.from()))
                .addValue("to", Timestamp.from(filter.to()));
        if (!filter.creditorIds().isEmpty()) {
            sql.append(" and creditor_id in (:creditors)");
            p.addValue("creditors", new ArrayList<>(filter.creditorIds()));
        }
        if (filter.portfolioId() != null) {
            sql.append(" and portfolio_id = :portfolio");
            p.addValue("portfolio", filter.portfolioId());
        }
        if (filter.personType() != null) {
            sql.append(" and person_type = :personType");
            p.addValue("personType", filter.personType());
        }
        return p;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
