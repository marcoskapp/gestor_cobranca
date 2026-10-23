package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.CampaignPanel;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.MessageTotals;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.OutcomeTotals;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.StepBreakdown;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.StepTotals;
import br.com.gestorcobranca.campaign.application.port.out.CampaignPanelQueries;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Painel de execução sobre as tabelas do módulo. Resultados (pagamentos, acordos) são contados uma vez
 * por referência na campanha, mesmo quando atribuídos a mais de uma inscrição do devedor.
 */
@Component
class JdbcCampaignPanelQueries implements CampaignPanelQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCampaignPanelQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CampaignPanel panel(UUID campaignId, Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource("campaignId", campaignId);
        Map<String, Long> byStatus = grouped("""
                select status as k, count(*) as n from campaign.enrollment where campaign_id = :campaignId group by status
                """, p);
        Map<String, Long> paused = grouped("""
                select pause_reason as k, count(*) as n from campaign.enrollment
                 where campaign_id = :campaignId and status = 'PAUSED' group by pause_reason
                """, p);
        Map<String, Long> ended = grouped("""
                select end_reason as k, count(*) as n from campaign.enrollment
                 where campaign_id = :campaignId and status = 'ENDED' group by end_reason
                """, p);
        Long suppressed = jdbc.queryForObject(
                "select count(*) from campaign.enrollment_suppression where campaign_id = :campaignId", p, Long.class);
        List<StepBreakdown> byStep = jdbc.query("""
                select step_code, action_type, channel,
                       count(*) filter (where outcome = 'EXECUTED') as executed,
                       count(*) filter (where outcome = 'SKIPPED') as skipped,
                       count(*) filter (where outcome = 'FAILED') as failed
                  from campaign.step_execution where campaign_id = :campaignId
                 group by step_code, action_type, channel order by step_code
                """, p, (rs, i) -> new StepBreakdown(rs.getString("step_code"), rs.getString("action_type"),
                rs.getString("channel"), rs.getLong("executed"), rs.getLong("skipped"), rs.getLong("failed")));
        StepTotals steps = new StepTotals(byStep.stream().mapToLong(StepBreakdown::executed).sum(),
                byStep.stream().mapToLong(StepBreakdown::skipped).sum(),
                byStep.stream().mapToLong(StepBreakdown::failed).sum());
        Map<String, Long> skipped = grouped("""
                select reason as k, count(*) as n from campaign.step_execution
                 where campaign_id = :campaignId and outcome = 'SKIPPED' group by reason
                """, p);
        Map<String, Long> failed = grouped("""
                select reason as k, count(*) as n from campaign.step_execution
                 where campaign_id = :campaignId and outcome = 'FAILED' group by reason
                """, p);
        MessageTotals messages = jdbc.queryForObject("""
                select count(*) filter (where outcome = 'EXECUTED' and message_status in ('SENT', 'DELIVERED', 'READ')) as sent,
                       count(*) filter (where outcome = 'EXECUTED' and message_status in ('DELIVERED', 'READ')) as delivered,
                       count(*) filter (where outcome = 'EXECUTED' and message_status = 'READ') as read,
                       count(*) filter (where outcome = 'EXECUTED' and message_status = 'FAILED') as failed,
                       count(*) filter (where outcome = 'SKIPPED' and reason = 'CONTACT_DENIED' and message_id is not null) as blocked
                  from campaign.step_execution where campaign_id = :campaignId and message_id is not null
                """, p, (rs, i) -> new MessageTotals(rs.getLong("sent"), rs.getLong("delivered"), rs.getLong("read"),
                rs.getLong("failed"), rs.getLong("blocked")));
        OutcomeTotals outcomes = jdbc.queryForObject("""
                with refs as (
                    select distinct on (kind, reference_id) kind, reference_id, amount
                      from campaign.attribution where campaign_id = :campaignId
                     order by kind, reference_id, occurred_at
                ), paying as (
                    select enrollment_id from campaign.attribution
                     where campaign_id = :campaignId and kind in ('PAYMENT', 'PAYMENT_REVERSAL')
                     group by enrollment_id having sum(amount) > 0
                )
                select (select count(distinct enrollment_id) from campaign.attribution
                         where campaign_id = :campaignId and kind = 'REPLY') as replied,
                       (select count(distinct enrollment_id) from campaign.attribution
                         where campaign_id = :campaignId and kind = 'EFFECTIVE_CONTACT') as contacts,
                       (select count(*) from refs where kind = 'AGREEMENT') as agreements,
                       (select coalesce(sum(amount), 0) from refs where kind = 'AGREEMENT') as agreement_amount,
                       (select count(*) from paying) as paying,
                       (select coalesce(sum(amount), 0) from refs where kind in ('PAYMENT', 'PAYMENT_REVERSAL')) as recovered
                """, p, (rs, i) -> new OutcomeTotals(rs.getLong("replied"), rs.getLong("contacts"), rs.getLong("agreements"),
                rs.getBigDecimal("agreement_amount"), rs.getLong("paying"), rs.getBigDecimal("recovered")));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new CampaignPanel(campaignId, now, total, byStatus, paused, ended, suppressed == null ? 0 : suppressed,
                steps, byStep, skipped, failed, messages, outcomes);
    }

    private Map<String, Long> grouped(String sql, MapSqlParameterSource params) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            result.put(rs.getString("k") == null ? "NONE" : rs.getString("k"), rs.getLong("n"));
        });
        return result;
    }
}
