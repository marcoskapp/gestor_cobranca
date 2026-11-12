package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.application.port.out.UsageReader;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.costing.domain.model.Usage.AiUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.CallUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.DirectTime;
import br.com.gestorcobranca.costing.domain.model.Usage.LoggedTime;
import br.com.gestorcobranca.costing.domain.model.Usage.MessageCount;
import br.com.gestorcobranca.costing.domain.model.Usage.Posted;
import br.com.gestorcobranca.costing.domain.model.Usage.TemplateSend;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lê o uso pelas views de contrato {@code costing.src_*} (security_invoker: RLS da assessoria corrente). Os dias são
 * calculados no fuso de operação ({@link TimeConfig#BUSINESS_ZONE}); as agregações só comprimem as linhas — as regras
 * de custo ficam no domínio.
 */
@Repository
class JdbcUsageReader implements UsageReader {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcUsageReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static MapSqlParameterSource period(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("from", from, Types.DATE)
                .addValue("to", to, Types.DATE)
                .addValue("fromTs", start(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toTs", start(to.plusDays(1)), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("zone", TimeConfig.BUSINESS_ZONE_ID);
    }

    static OffsetDateTime start(LocalDate day) {
        return day.atStartOfDay(TimeConfig.BUSINESS_ZONE).toOffsetDateTime();
    }

    @Override
    public List<DirectTime> directTime(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = period(from, to).addValue("queuedFromTs", start(from.minusDays(1)),
                Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbc.query("""
                select t.day, t.user_id, t.creditor_id, t.portfolio_id, sum(t.seconds) as seconds
                  from (select (i.started_at at time zone :zone)::date as day, i.operator_id as user_id, i.creditor_id,
                               i.portfolio_id, i.duration_seconds::bigint as seconds
                          from costing.src_interaction i
                         where i.actor_type = 'USER' and i.operator_id is not null and i.duration_seconds > 0
                           and i.started_at >= :fromTs and i.started_at < :toTs
                        union all
                        select (coalesce(c.ended_at, c.queued_at) at time zone :zone)::date, c.operator_id, c.creditor_id,
                               c.portfolio_id, c.wrap_up_seconds::bigint
                          from costing.src_call c
                         where c.operator_id is not null and c.creditor_id is not null and c.wrap_up_seconds > 0
                           and c.queued_at >= :queuedFromTs and c.queued_at < :toTs
                           and coalesce(c.ended_at, c.queued_at) >= :fromTs
                           and coalesce(c.ended_at, c.queued_at) < :toTs) t
                 group by t.day, t.user_id, t.creditor_id, t.portfolio_id
                """, p, (rs, i) -> new DirectTime(day(rs), uuid(rs, "user_id"), uuid(rs, "creditor_id"),
                uuid(rs, "portfolio_id"), rs.getLong("seconds")));
    }

    @Override
    public List<LoggedTime> loggedTime(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select l.fact_date as day, l.user_id, l.seconds
                  from costing.src_logged_time l
                 where l.fact_date between :from and :to and l.seconds > 0
                """, period(from, to), (rs, i) -> new LoggedTime(day(rs), uuid(rs, "user_id"), rs.getLong("seconds")));
    }

    @Override
    public List<CallUsage> calls(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select (c.queued_at at time zone :zone)::date as day, c.creditor_id, c.portfolio_id,
                       c.provider_account_id, a.rate_per_minute, c.talk_seconds, count(*) as calls
                  from costing.src_call c
                  join costing.src_provider_account a on a.provider_account_id = c.provider_account_id
                 where c.talk_seconds > 0 and c.queued_at >= :fromTs and c.queued_at < :toTs
                 group by 1, 2, 3, 4, 5, 6
                """, period(from, to), (rs, i) -> new CallUsage(day(rs), uuid(rs, "creditor_id"),
                uuid(rs, "portfolio_id"), uuid(rs, "provider_account_id"), rs.getBigDecimal("rate_per_minute"),
                rs.getInt("talk_seconds"), rs.getLong("calls")));
    }

    @Override
    public List<AiUsage> ai(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select x.day, x.creditor_id, x.portfolio_id, x.currency, sum(x.amount) as amount
                  from (select (t.started_at at time zone :zone)::date as day, t.creditor_id, t.portfolio_id,
                               upper(coalesce(t.cost_currency, 'USD')) as currency, t.cost_amount as amount
                          from costing.src_assistant_cost t
                         where t.cost_amount > 0 and t.started_at >= :fromTs and t.started_at < :toTs
                        union all
                        select (p.created_at at time zone :zone)::date, p.creditor_id, p.portfolio_id,
                               upper(coalesce(p.ai_currency, 'USD')), p.ai_cost
                          from costing.src_pdf_import_cost p
                         where p.ai_cost > 0 and p.created_at >= :fromTs and p.created_at < :toTs) x
                 group by 1, 2, 3, 4
                """, period(from, to), (rs, i) -> new AiUsage(day(rs), uuid(rs, "creditor_id"), uuid(rs, "portfolio_id"),
                rs.getString("currency"), rs.getBigDecimal("amount")));
    }

    @Override
    public List<MessageCount> messages(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select (m.sent_at at time zone :zone)::date as day, m.creditor_id, m.portfolio_id, m.channel,
                       count(*) as messages
                  from costing.src_message m
                 where m.direction = 'OUTBOUND' and m.channel in ('SMS', 'EMAIL')
                   and m.sent_at >= :fromTs and m.sent_at < :toTs
                 group by 1, 2, 3, 4
                """, period(from, to), (rs, i) -> new MessageCount(day(rs), uuid(rs, "creditor_id"),
                uuid(rs, "portfolio_id"), TariffChannel.valueOf(rs.getString("channel")), rs.getLong("messages")));
    }

    @Override
    public void templateSends(LocalDate from, LocalDate to, Consumer<TemplateSend> consumer) {
        RowCallbackHandler handler = rs -> consumer.accept(new TemplateSend(uuid(rs, "conversation_id"),
                rs.getString("provider_category"), rs.getObject("sent_at", OffsetDateTime.class).toInstant(), day(rs),
                uuid(rs, "creditor_id"), uuid(rs, "portfolio_id")));
        jdbc.query("""
                select m.conversation_id, m.provider_category, m.sent_at, (m.sent_at at time zone :zone)::date as day,
                       m.creditor_id, m.portfolio_id
                  from costing.src_message m
                 where m.channel = 'WHATSAPP' and m.direction = 'OUTBOUND' and m.template_id is not null
                   and m.sent_at >= :fromTs and m.sent_at < :toTs
                 order by m.conversation_id, m.provider_category, m.sent_at
                """, period(from, to), handler);
    }

    @Override
    public List<Posted> variableCommission(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select x.day, x.creditor_id, x.portfolio_id, sum(x.credited_amount) as amount, count(*) as entries
                  from (select case when e.kind = 'REVERSAL' then (e.created_at at time zone :zone)::date
                                    else e.paid_on end as day,
                               e.creditor_id, e.portfolio_id, e.credited_amount
                          from costing.src_commission_ledger e
                         where e.status <> 'WAIVED'
                           and ((e.kind = 'REVERSAL' and e.created_at >= :fromTs and e.created_at < :toTs)
                                or (e.kind <> 'REVERSAL' and e.paid_on between :from and :to))) x
                 group by 1, 2, 3
                """, period(from, to), JdbcUsageReader::posted);
    }

    @Override
    public List<Posted> legalExpenses(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select e.incurred_on as day, e.creditor_id, e.portfolio_id, sum(e.amount) as amount, count(*) as entries
                  from costing.src_legal_expense e
                 where e.advanced_by = 'AGENCY' and not e.reimbursable and e.status <> 'CANCELLED'
                   and e.incurred_on between :from and :to
                 group by 1, 2, 3
                """, period(from, to), JdbcUsageReader::posted);
    }

    private static Posted posted(ResultSet rs, int row) throws SQLException {
        return new Posted(day(rs), uuid(rs, "creditor_id"), uuid(rs, "portfolio_id"), rs.getBigDecimal("amount"),
                rs.getLong("entries"));
    }

    static LocalDate day(ResultSet rs) throws SQLException {
        return rs.getObject("day", LocalDate.class);
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
