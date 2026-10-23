package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.application.port.out.EnrollmentSuppressions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
class JdbcEnrollmentSuppressions implements EnrollmentSuppressions {

    private final JdbcTemplate jdbc;

    JdbcEnrollmentSuppressions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID campaignId, UUID caseId, UUID blockingCampaignId, Instant at) {
        jdbc.update("""
                insert into campaign.enrollment_suppression
                  (tenant_id, campaign_id, case_id, reason, blocking_campaign_id, first_seen_at, last_seen_at, occurrences)
                values (shared.current_tenant_id(), ?, ?, 'CONCURRENT_CAMPAIGN', ?, ?, ?, 1)
                on conflict (tenant_id, campaign_id, case_id) do update
                   set last_seen_at = excluded.last_seen_at, blocking_campaign_id = excluded.blocking_campaign_id,
                       occurrences = campaign.enrollment_suppression.occurrences + 1
                """, campaignId, caseId, blockingCampaignId, Timestamp.from(at), Timestamp.from(at));
    }
}
