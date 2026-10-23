package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tabelas de fatos do módulo via SQL. O tenant vem de {@code shared.current_tenant_id()} (o mesmo
 * valor que o RLS exige), definido pelo gerenciador de transações.
 */
@Repository
class JdbcFactStore implements FactStore {

    private static final Set<UUID> NO_FILTER = Set.of(new UUID(0, 0));

    private final NamedParameterJdbcTemplate jdbc;

    JdbcFactStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void increment(FactKey key, Measures m) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("date", key.date(), Types.DATE)
                .addValue("user", key.userId())
                .addValue("creditor", key.creditorId(), Types.OTHER)
                .addValue("portfolio", key.portfolioId(), Types.OTHER)
                .addValue("bucket", (key.delayBucket() == null ? DelayBucket.UNKNOWN : key.delayBucket()).name())
                .addValue("channel", key.channel() == null || key.channel().isBlank() ? "UNKNOWN" : key.channel())
                .addValue("attempts", m.attempts())
                .addValue("cpc", m.effectiveContacts())
                .addValue("calls", m.answeredCalls())
                .addValue("talk", m.talkSeconds())
                .addValue("agreements", m.agreements())
                .addValue("negotiated", zero(m.negotiatedAmount()))
                .addValue("agreementAmount", zero(m.agreementAmount()))
                .addValue("balance", zero(m.updatedBalance()))
                .addValue("discount", zero(m.discountAmount()))
                .addValue("downPayment", zero(m.downPaymentAmount()))
                .addValue("envelopeSum", zero(m.envelopeUsageSum()))
                .addValue("envelopeCount", m.envelopeCount())
                .addValue("concessionSum", m.concessionLevelSum())
                .addValue("concessionCount", m.concessionCount())
                .addValue("firstInstallments", m.firstInstallments())
                .addValue("accrued", zero(m.accruedAmount()))
                .addValue("reversed", zero(m.reversedAmount()));
        jdbc.update("""
                insert into incentive.performance_fact (tenant_id, fact_date, user_id, creditor_id, portfolio_id,
                    delay_bucket, channel, attempts, effective_contacts, answered_calls, talk_seconds, agreements,
                    negotiated_amount, agreement_amount, updated_balance, discount_amount, down_payment_amount,
                    envelope_usage_sum, envelope_count, concession_level_sum, concession_count, first_installments,
                    accrued_amount, reversed_amount)
                values (shared.current_tenant_id(), :date, :user, :creditor, :portfolio, :bucket, :channel, :attempts,
                    :cpc, :calls, :talk, :agreements, :negotiated, :agreementAmount, :balance, :discount, :downPayment,
                    :envelopeSum, :envelopeCount, :concessionSum, :concessionCount, :firstInstallments, :accrued, :reversed)
                on conflict (tenant_id, fact_date, user_id, creditor_id, portfolio_id, delay_bucket, channel) do update set
                    attempts = performance_fact.attempts + excluded.attempts,
                    effective_contacts = performance_fact.effective_contacts + excluded.effective_contacts,
                    answered_calls = performance_fact.answered_calls + excluded.answered_calls,
                    talk_seconds = performance_fact.talk_seconds + excluded.talk_seconds,
                    agreements = performance_fact.agreements + excluded.agreements,
                    negotiated_amount = performance_fact.negotiated_amount + excluded.negotiated_amount,
                    agreement_amount = performance_fact.agreement_amount + excluded.agreement_amount,
                    updated_balance = performance_fact.updated_balance + excluded.updated_balance,
                    discount_amount = performance_fact.discount_amount + excluded.discount_amount,
                    down_payment_amount = performance_fact.down_payment_amount + excluded.down_payment_amount,
                    envelope_usage_sum = performance_fact.envelope_usage_sum + excluded.envelope_usage_sum,
                    envelope_count = performance_fact.envelope_count + excluded.envelope_count,
                    concession_level_sum = performance_fact.concession_level_sum + excluded.concession_level_sum,
                    concession_count = performance_fact.concession_count + excluded.concession_count,
                    first_installments = performance_fact.first_installments + excluded.first_installments,
                    accrued_amount = performance_fact.accrued_amount + excluded.accrued_amount,
                    reversed_amount = performance_fact.reversed_amount + excluded.reversed_amount
                """, p);
    }

    @Override
    public void recordWorkedCase(LocalDate date, UUID userId, UUID caseId, UUID creditorId, UUID portfolioId, String channel) {
        jdbc.update("""
                insert into incentive.worked_case_day (tenant_id, fact_date, user_id, case_id, creditor_id, portfolio_id, channel)
                values (shared.current_tenant_id(), :date, :user, :case, :creditor, :portfolio, :channel)
                on conflict do nothing
                """, new MapSqlParameterSource().addValue("date", date, Types.DATE).addValue("user", userId)
                .addValue("case", caseId).addValue("creditor", creditorId, Types.OTHER)
                .addValue("portfolio", portfolioId, Types.OTHER)
                .addValue("channel", channel == null ? "UNKNOWN" : channel));
    }

    @Override
    public void recordEffectiveContact(UUID interactionId, UUID caseId, UUID operatorId, Instant occurredAt) {
        jdbc.update("""
                insert into incentive.effective_contact (tenant_id, interaction_id, case_id, operator_id, occurred_at)
                values (shared.current_tenant_id(), :interaction, :case, :operator, :at)
                on conflict do nothing
                """, new MapSqlParameterSource().addValue("interaction", interactionId).addValue("case", caseId)
                .addValue("operator", operatorId).addValue("at", ts(occurredAt), Types.TIMESTAMP_WITH_TIMEZONE));
    }

    @Override
    public Optional<UUID> lastEffectiveContact(UUID caseId, Instant from, Instant to) {
        return jdbc.query("""
                        select operator_id from incentive.effective_contact
                        where case_id = :case and occurred_at >= :from and occurred_at <= :to
                        order by occurred_at desc limit 1
                        """, new MapSqlParameterSource().addValue("case", caseId)
                        .addValue("from", ts(from), Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("to", ts(to), Types.TIMESTAMP_WITH_TIMEZONE),
                (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    @Override
    public void openConversationWait(UUID conversationId, Instant since) {
        jdbc.update("""
                insert into incentive.conversation_wait (tenant_id, conversation_id, waiting_since)
                values (shared.current_tenant_id(), :conversation, :since)
                on conflict (tenant_id, conversation_id) do update
                    set waiting_since = least(conversation_wait.waiting_since, excluded.waiting_since)
                """, new MapSqlParameterSource().addValue("conversation", conversationId)
                .addValue("since", ts(since), Types.TIMESTAMP_WITH_TIMEZONE));
    }

    @Override
    public Optional<Instant> closeConversationWait(UUID conversationId) {
        return jdbc.query("""
                        delete from incentive.conversation_wait where conversation_id = :conversation
                        returning waiting_since
                        """, new MapSqlParameterSource().addValue("conversation", conversationId),
                (rs, i) -> rs.getTimestamp(1).toInstant()).stream().findFirst();
    }

    @Override
    public void recordResponseSample(UUID messageId, LocalDate date, UUID userId, UUID creditorId, UUID portfolioId,
                                     String channel, long seconds) {
        jdbc.update("""
                insert into incentive.response_sample (tenant_id, message_id, fact_date, user_id, creditor_id, portfolio_id,
                    channel, response_seconds)
                values (shared.current_tenant_id(), :message, :date, :user, :creditor, :portfolio, :channel, :seconds)
                on conflict do nothing
                """, new MapSqlParameterSource().addValue("message", messageId).addValue("date", date, Types.DATE)
                .addValue("user", userId).addValue("creditor", creditorId, Types.OTHER)
                .addValue("portfolio", portfolioId, Types.OTHER).addValue("channel", channel == null ? "UNKNOWN" : channel)
                .addValue("seconds", Math.max(0, seconds)));
    }

    @Override
    public void replaceLoggedTime(LocalDate from, LocalDate to, Collection<UUID> userIds,
                                  Map<UUID, Map<LocalDate, Long>> seconds) {
        if (userIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                delete from incentive.logged_time_daily where fact_date between :from and :to and user_id in (:users)
                """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE)
                .addValue("users", userIds));
        List<MapSqlParameterSource> rows = new ArrayList<>();
        seconds.forEach((user, days) -> days.forEach((day, value) -> {
            if (!day.isBefore(from) && !day.isAfter(to) && value > 0) {
                rows.add(new MapSqlParameterSource().addValue("date", day, Types.DATE).addValue("user", user)
                        .addValue("seconds", Math.min(86_400L, value)));
            }
        }));
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into incentive.logged_time_daily (tenant_id, fact_date, user_id, seconds)
                    values (shared.current_tenant_id(), :date, :user, :seconds)
                    """, rows.toArray(MapSqlParameterSource[]::new));
        }
    }

    @Override
    public Map<UUID, Integer> workedDays(Collection<UUID> userIds, LocalDate from, LocalDate to) {
        Map<UUID, Integer> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select user_id, count(*) from incentive.logged_time_daily
                where user_id in (:users) and fact_date between :from and :to and seconds > 0
                group by user_id
                """, new MapSqlParameterSource().addValue("users", userIds).addValue("from", from, Types.DATE)
                .addValue("to", to, Types.DATE), rs -> {
            result.put(rs.getObject(1, UUID.class), rs.getInt(2));
        });
        return result;
    }

    @Override
    public void ensurePeriod(LocalDate start, LocalDate end) {
        jdbc.update("""
                insert into incentive.commission_period (id, tenant_id, version, created_at, created_by, updated_at,
                    updated_by, period_start, period_end, status)
                values (:id, shared.current_tenant_id(), 0, now(), shared.current_actor_id(), now(),
                    shared.current_actor_id(), :start, :end, 'OPEN')
                on conflict (tenant_id, period_start) do nothing
                """, new MapSqlParameterSource().addValue("id", br.com.gestorcobranca.shared.domain.Ids.newId())
                .addValue("start", start, Types.DATE).addValue("end", end, Types.DATE));
    }

    @Override
    public void recordCaseSnapshot(CaseSnapshot s) {
        jdbc.update("""
                insert into incentive.case_snapshot (tenant_id, source_event_id, case_id, effective_at, creditor_id,
                    portfolio_id, assignee_id, team_id, status, eligible, eligible_balance)
                select shared.current_tenant_id(), :event, :case, :at,
                       coalesce(:creditor, prev.creditor_id), coalesce(:portfolio, prev.portfolio_id), :assignee,
                       coalesce(:team, prev.team_id), coalesce(:status, prev.status), coalesce(:eligible, prev.eligible),
                       coalesce(:balance, prev.eligible_balance)
                from (select 1) one
                left join lateral (select c.creditor_id, c.portfolio_id, c.team_id, c.status, c.eligible, c.eligible_balance
                                   from incentive.case_snapshot c where c.case_id = :case and c.effective_at <= :at
                                   order by c.effective_at desc limit 1) prev on true
                on conflict do nothing
                """, new MapSqlParameterSource().addValue("event", s.sourceEventId()).addValue("case", s.caseId())
                .addValue("at", ts(s.effectiveAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("creditor", s.creditorId(), Types.OTHER).addValue("portfolio", s.portfolioId(), Types.OTHER)
                .addValue("assignee", s.assigneeId(), Types.OTHER).addValue("team", s.teamId(), Types.OTHER)
                .addValue("status", s.status(), Types.VARCHAR).addValue("eligible", s.eligible(), Types.BOOLEAN)
                .addValue("balance", s.eligibleBalance(), Types.NUMERIC));
    }

    @Override
    public Optional<UUID> assigneeAt(UUID caseId, Instant at) {
        return jdbc.query("""
                        select assignee_id from incentive.case_snapshot
                        where case_id = :case and effective_at <= :at order by effective_at desc limit 1
                        """, new MapSqlParameterSource().addValue("case", caseId)
                        .addValue("at", ts(at), Types.TIMESTAMP_WITH_TIMEZONE),
                (rs, i) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
                .stream().findFirst().flatMap(o -> o);
    }

    @Override
    public void agreementStatus(UUID agreementId, String status, Instant at, BigDecimal totalAmount, UUID replacedAgreementId) {
        jdbc.update("""
                insert into incentive.agreement_lifecycle (tenant_id, agreement_id, status, status_at, formalized_at,
                    broken_at, renegotiated_at, replaced_agreement_id, total_amount)
                values (shared.current_tenant_id(), :agreement, :status, :at, :formalizedAt, :brokenAt, :renegotiatedAt,
                    :replaced, :total)
                on conflict (tenant_id, agreement_id) do update set
                    status = case when agreement_lifecycle.status_at is null or excluded.status_at >= agreement_lifecycle.status_at
                                  then excluded.status else agreement_lifecycle.status end,
                    status_at = greatest(agreement_lifecycle.status_at, excluded.status_at),
                    formalized_at = coalesce(agreement_lifecycle.formalized_at, excluded.formalized_at),
                    broken_at = coalesce(agreement_lifecycle.broken_at, excluded.broken_at),
                    renegotiated_at = coalesce(agreement_lifecycle.renegotiated_at, excluded.renegotiated_at),
                    replaced_agreement_id = coalesce(agreement_lifecycle.replaced_agreement_id, excluded.replaced_agreement_id),
                    total_amount = coalesce(excluded.total_amount, agreement_lifecycle.total_amount)
                """, new MapSqlParameterSource().addValue("agreement", agreementId).addValue("status", status)
                .addValue("at", ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("formalizedAt", "FORMALIZED".equals(status) ? ts(at) : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("brokenAt", "BROKEN".equals(status) ? ts(at) : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("renegotiatedAt", "RENEGOTIATED".equals(status) ? ts(at) : null, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("replaced", replacedAgreementId, Types.OTHER)
                .addValue("total", totalAmount, Types.NUMERIC));
    }

    @Override
    public void agreementFormalized(UUID agreementId, Instant formalizedAt, BigDecimal totalAmount, UUID replacedAgreementId) {
        jdbc.update("""
                insert into incentive.agreement_lifecycle (tenant_id, agreement_id, formalized_at, replaced_agreement_id,
                    total_amount)
                values (shared.current_tenant_id(), :agreement, :at, :replaced, :total)
                on conflict (tenant_id, agreement_id) do update set
                    formalized_at = coalesce(agreement_lifecycle.formalized_at, excluded.formalized_at),
                    replaced_agreement_id = coalesce(agreement_lifecycle.replaced_agreement_id, excluded.replaced_agreement_id),
                    total_amount = coalesce(agreement_lifecycle.total_amount, excluded.total_amount)
                """, new MapSqlParameterSource().addValue("agreement", agreementId)
                .addValue("at", ts(formalizedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("replaced", replacedAgreementId, Types.OTHER).addValue("total", totalAmount, Types.NUMERIC));
    }

    @Override
    public boolean firstInstallmentPaid(UUID agreementId, Instant paidAt) {
        List<Boolean> previous = jdbc.query("""
                        select first_installment_paid_at is not null from incentive.agreement_lifecycle
                        where agreement_id = :agreement for update
                        """, new MapSqlParameterSource().addValue("agreement", agreementId), (rs, i) -> rs.getBoolean(1));
        if (!previous.isEmpty() && previous.getFirst()) {
            return false;
        }
        jdbc.update("""
                insert into incentive.agreement_lifecycle (tenant_id, agreement_id, first_installment_paid_at)
                values (shared.current_tenant_id(), :agreement, :at)
                on conflict (tenant_id, agreement_id) do update set
                    first_installment_paid_at = coalesce(agreement_lifecycle.first_installment_paid_at,
                                                         excluded.first_installment_paid_at)
                """, new MapSqlParameterSource().addValue("agreement", agreementId)
                .addValue("at", ts(paidAt), Types.TIMESTAMP_WITH_TIMEZONE));
        return true;
    }

    @Override
    public void firstInstallmentReverted(UUID agreementId) {
        jdbc.update("update incentive.agreement_lifecycle set first_installment_paid_at = null where agreement_id = :agreement",
                new MapSqlParameterSource().addValue("agreement", agreementId));
    }

    @Override
    public Optional<Instant> firstInstallmentPaidAt(UUID agreementId) {
        return jdbc.query("""
                        select first_installment_paid_at from incentive.agreement_lifecycle
                        where agreement_id = :agreement and first_installment_paid_at is not null
                        """, new MapSqlParameterSource().addValue("agreement", agreementId),
                (rs, i) -> rs.getTimestamp(1).toInstant()).stream().findFirst();
    }

    @Override
    public Safra safra(UUID closerId, Instant formalizedFrom, Instant formalizedTo, int windowDays) {
        return jdbc.queryForObject("""
                select count(*),
                       count(*) filter (where l.broken_at is not null
                                        and l.broken_at <= a.formalized_at + make_interval(days => :window))
                from incentive.agreement_attribution a
                join incentive.attribution_participant p on p.attribution_id = a.id and p.role = 'CLOSER'
                     and p.actor_type = 'USER' and p.actor_id = :closer
                left join incentive.agreement_lifecycle l on l.tenant_id = a.tenant_id and l.agreement_id = a.agreement_id
                where a.formalized_at >= :from and a.formalized_at < :to
                """, new MapSqlParameterSource().addValue("closer", closerId).addValue("window", windowDays)
                .addValue("from", ts(formalizedFrom), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", ts(formalizedTo), Types.TIMESTAMP_WITH_TIMEZONE),
                (rs, i) -> new Safra(rs.getInt(1), rs.getInt(2)));
    }

    @Override
    public EnvelopeUsage envelopeUsage(UUID closerId, Instant formalizedFrom, Instant formalizedTo) {
        return jdbc.queryForObject("""
                select count(a.envelope_usage), avg(a.envelope_usage)
                from incentive.agreement_attribution a
                join incentive.attribution_participant p on p.attribution_id = a.id and p.role = 'CLOSER'
                     and p.actor_type = 'USER' and p.actor_id = :closer
                where a.formalized_at >= :from and a.formalized_at < :to
                """, new MapSqlParameterSource().addValue("closer", closerId)
                .addValue("from", ts(formalizedFrom), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", ts(formalizedTo), Types.TIMESTAMP_WITH_TIMEZONE),
                (rs, i) -> new EnvelopeUsage(rs.getInt(1),
                        rs.getBigDecimal(2) == null ? null : rs.getBigDecimal(2).setScale(4, java.math.RoundingMode.HALF_EVEN)));
    }

    @Override
    public BigDecimal firstInstallmentCreditWeight(UUID userId, Instant from, Instant to) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(c.weight * c.renegotiation_factor / 10000), 0)
                from incentive.agreement_attribution a
                join incentive.attribution_credit c on c.attribution_id = a.id and c.beneficiary_type = 'USER'
                     and c.beneficiary_id = :user
                join incentive.agreement_lifecycle l on l.tenant_id = a.tenant_id and l.agreement_id = a.agreement_id
                where l.first_installment_paid_at >= :from and l.first_installment_paid_at < :to
                """, new MapSqlParameterSource().addValue("user", userId)
                .addValue("from", ts(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", ts(to), Types.TIMESTAMP_WITH_TIMEZONE), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    @Override
    public BigDecimal eligibleBalanceAt(Collection<UUID> userIds, Instant at, Collection<UUID> creditorIds) {
        return eligibleBalance(jdbc, userIds, at, creditorIds);
    }

    /** Saldo elegível dos casos dos usuários no instante (último retrato de cada caso). */
    static BigDecimal eligibleBalance(NamedParameterJdbcTemplate jdbc, Collection<UUID> userIds, Instant at,
                                      Collection<UUID> creditorIds) {
        if (userIds == null || userIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        boolean allCreditors = creditorIds == null || creditorIds.isEmpty();
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(b.eligible_balance), 0)
                from (select distinct on (case_id) case_id, assignee_id, eligible, creditor_id
                      from incentive.case_snapshot where effective_at <= :at
                      order by case_id, effective_at desc) s
                cross join lateral (select c.eligible_balance from incentive.case_snapshot c
                                    where c.case_id = s.case_id and c.effective_at <= :at and c.eligible_balance is not null
                                    order by c.effective_at desc limit 1) b
                where s.assignee_id in (:users) and coalesce(s.eligible, true)
                  and (:allCreditors or s.creditor_id in (:creditors))
                """, new MapSqlParameterSource().addValue("users", userIds)
                .addValue("at", ts(at), Types.TIMESTAMP_WITH_TIMEZONE).addValue("allCreditors", allCreditors)
                .addValue("creditors", allCreditors ? NO_FILTER : creditorIds), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
