package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.AudienceCount;
import br.com.gestorcobranca.campaign.application.port.out.AudienceStore;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model de audiência em {@code campaign.audience_case}. A consulta dos segmentos reproduz a
 * semântica de {@code SegmentMatcher} (atraso = dias desde o vencimento mais antigo no fuso de
 * operação, nunca negativo; "sem interação há N dias" inclui nunca; score exigido exclui casos sem
 * score). RLS restringe ao tenant da transação.
 */
@Component
class JdbcAudienceStore implements AudienceStore {

    private static final String COLUMNS = """
            a.case_id, a.debtor_id, a.creditor_id, a.portfolio_id, a.status, a.resume_status, a.eligible,
            a.eligibility_reasons, a.contactable_channels, a.open_balance, a.eligible_balance, a.oldest_due_date,
            a.owner_type, a.interaction_count, a.effective_contact_count, a.last_interaction_at,
            a.last_effective_contact_at, a.score, a.broken_agreements, a.case_version, a.refreshed_at
            """;
    private static final String DAYS_OVERDUE = "greatest(0, coalesce(cast(:today as date) - a.oldest_due_date, 0))";

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActor currentActor;

    JdbcAudienceStore(NamedParameterJdbcTemplate jdbc, CurrentActor currentActor) {
        this.jdbc = jdbc;
        this.currentActor = currentActor;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(AudienceSnapshot s) {
        jdbc.update("""
                insert into campaign.audience_case
                  (tenant_id, case_id, debtor_id, creditor_id, portfolio_id, status, resume_status, eligible,
                   eligibility_reasons, contactable_channels, open_balance, eligible_balance, oldest_due_date, owner_type,
                   interaction_count, effective_contact_count, last_interaction_at, last_effective_contact_at, score,
                   broken_agreements, case_version, refreshed_at)
                values (:tenant, :caseId, :debtorId, :creditorId, :portfolioId, :status, :resumeStatus, :eligible,
                        :reasons, :channels, :openBalance, :eligibleBalance, :oldestDueDate, :ownerType,
                        :interactions, :effectiveContacts, :lastInteraction, :lastEffective, :score,
                        :brokenAgreements, :caseVersion, :refreshedAt)
                on conflict (tenant_id, case_id) do update set
                   status = excluded.status, resume_status = excluded.resume_status, eligible = excluded.eligible,
                   eligibility_reasons = excluded.eligibility_reasons,
                   contactable_channels = excluded.contactable_channels, open_balance = excluded.open_balance,
                   eligible_balance = excluded.eligible_balance, oldest_due_date = excluded.oldest_due_date,
                   owner_type = excluded.owner_type, interaction_count = excluded.interaction_count,
                   effective_contact_count = excluded.effective_contact_count,
                   last_interaction_at = excluded.last_interaction_at,
                   last_effective_contact_at = excluded.last_effective_contact_at,
                   score = case when campaign.audience_case.score_calculated_at is null then excluded.score
                                else campaign.audience_case.score end,
                   case_version = excluded.case_version, refreshed_at = excluded.refreshed_at
                 where campaign.audience_case.case_version <= excluded.case_version
                """, new MapSqlParameterSource()
                .addValue("tenant", currentActor.tenantId())
                .addValue("caseId", s.caseId())
                .addValue("debtorId", s.debtorId())
                .addValue("creditorId", s.creditorId())
                .addValue("portfolioId", s.portfolioId())
                .addValue("status", s.status())
                .addValue("resumeStatus", s.resumeStatus())
                .addValue("eligible", s.eligible())
                .addValue("reasons", truncate(String.join(",", s.eligibilityReasons()), 500))
                .addValue("channels", truncate(String.join(",", s.contactableChannels()), 100))
                .addValue("openBalance", s.openBalance())
                .addValue("eligibleBalance", s.eligibleBalance())
                .addValue("oldestDueDate", s.oldestDueDate())
                .addValue("ownerType", s.ownerType())
                .addValue("interactions", s.interactionCount())
                .addValue("effectiveContacts", s.effectiveContactCount())
                .addValue("lastInteraction", timestamp(s.lastInteractionAt()))
                .addValue("lastEffective", timestamp(s.lastEffectiveContactAt()))
                .addValue("score", s.score())
                .addValue("brokenAgreements", s.brokenAgreements())
                .addValue("caseVersion", s.caseVersion())
                .addValue("refreshedAt", timestamp(s.refreshedAt())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AudienceSnapshot> find(UUID caseId) {
        return jdbc.query("select " + COLUMNS + " from campaign.audience_case a where a.case_id = :caseId",
                new MapSqlParameterSource("caseId", caseId), MAPPER).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyScore(UUID caseId, int score, Instant calculatedAt) {
        return jdbc.update("""
                update campaign.audience_case set score = :score, score_calculated_at = :calculatedAt
                 where case_id = :caseId and (score_calculated_at is null or score_calculated_at < :calculatedAt)
                """, new MapSqlParameterSource("caseId", caseId).addValue("score", score)
                .addValue("calculatedAt", timestamp(calculatedAt))) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementBrokenAgreements(UUID caseId) {
        jdbc.update("update campaign.audience_case set broken_agreements = broken_agreements + 1 where case_id = :caseId",
                new MapSqlParameterSource("caseId", caseId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AudienceCount count(Selection selection) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(selection, params);
        return jdbc.queryForObject("""
                select count(*) as cases, count(distinct a.debtor_id) as debtors,
                       coalesce(sum(a.open_balance), 0) as balance
                  from campaign.audience_case a where\s""" + where, params,
                (rs, i) -> new AudienceCount(rs.getLong("cases"), rs.getLong("debtors"), rs.getBigDecimal("balance"),
                        selection.now()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AudienceSnapshot> candidates(Selection selection, UUID campaignId, Integer reenrollAfterDays,
                                             boolean pausesOnAgreement, UUID afterCaseId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from campaign.audience_case a where ")
                .append(where(selection, params))
                // situações que pausariam a régua (PauseRules) não são inscritas
                .append(" and a.status not in ('LEGAL', 'SUSPENDED', 'DISPUTED')")
                .append(" and position(',DISPUTE_OPEN,' in ',' || a.eligibility_reasons || ',') = 0")
                .append(" and position(',CONTACT_RESTRICTED,' in ',' || a.eligibility_reasons || ',') = 0");
        if (pausesOnAgreement) {
            sql.append(" and not (a.status = 'AGREEMENT' or coalesce(a.resume_status, '') = 'AGREEMENT')");
        }
        params.addValue("campaignId", campaignId);
        sql.append(" and not exists (select 1 from campaign.enrollment e where e.campaign_id = :campaignId")
                .append(" and e.case_id = a.case_id");
        if (reenrollAfterDays != null) {
            params.addValue("reenrollCutoff", timestamp(selection.now().minus(Duration.ofDays(reenrollAfterDays))));
            sql.append(" and (e.status <> 'ENDED' or e.ended_at > :reenrollCutoff)");
        }
        sql.append(")");
        if (afterCaseId != null) {
            sql.append(" and a.case_id > :afterCaseId");
            params.addValue("afterCaseId", afterCaseId);
        }
        sql.append(" order by a.case_id limit :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    /** Cláusula WHERE equivalente a {@code SegmentMatcher.unmet(...)} vazio. */
    static String where(Selection selection, MapSqlParameterSource params) {
        SegmentCriteria c = selection.criteria();
        StringBuilder w = new StringBuilder("a.status <> 'CLOSED'");
        params.addValue("today", selection.today());
        if (selection.creditorId() != null) {
            w.append(" and a.creditor_id = :creditorId");
            params.addValue("creditorId", selection.creditorId());
        }
        if (selection.portfolioId() != null) {
            w.append(" and a.portfolio_id = :portfolioId");
            params.addValue("portfolioId", selection.portfolioId());
        }
        if (!c.portfolioIds().isEmpty()) {
            w.append(" and a.portfolio_id in (:portfolioIds)");
            params.addValue("portfolioIds", c.portfolioIds());
        }
        if (c.minDaysOverdue() != null) {
            w.append(" and ").append(DAYS_OVERDUE).append(" >= :minDays");
            params.addValue("minDays", c.minDaysOverdue());
        }
        if (c.maxDaysOverdue() != null) {
            w.append(" and ").append(DAYS_OVERDUE).append(" <= :maxDays");
            params.addValue("maxDays", c.maxDaysOverdue());
        }
        if (c.minBalance() != null) {
            w.append(" and a.open_balance >= :minBalance");
            params.addValue("minBalance", c.minBalance());
        }
        if (c.maxBalance() != null) {
            w.append(" and a.open_balance <= :maxBalance");
            params.addValue("maxBalance", c.maxBalance());
        }
        if (!c.caseStatuses().isEmpty()) {
            w.append(" and a.status in (:statuses)");
            params.addValue("statuses", c.caseStatuses());
        }
        if (Boolean.TRUE.equals(c.eligibleOnly())) {
            w.append(" and a.eligible");
        }
        if (c.contactableChannel() != null) {
            w.append(" and position(',' || cast(:channel as varchar) || ',' in ',' || a.contactable_channels || ',') > 0");
            params.addValue("channel", c.contactableChannel());
        }
        if (c.minInteractions() != null) {
            w.append(" and a.interaction_count >= :minInteractions");
            params.addValue("minInteractions", c.minInteractions());
        }
        if (c.maxInteractions() != null) {
            w.append(" and a.interaction_count <= :maxInteractions");
            params.addValue("maxInteractions", c.maxInteractions());
        }
        if (c.everEffectiveContact() != null) {
            w.append(c.everEffectiveContact() ? " and a.effective_contact_count > 0" : " and a.effective_contact_count = 0");
        }
        if (c.noInteractionDays() != null) {
            w.append(" and (a.last_interaction_at is null or a.last_interaction_at < :interactionCutoff)");
            params.addValue("interactionCutoff", timestamp(selection.now().minus(Duration.ofDays(c.noInteractionDays()))));
        }
        if (c.noEffectiveContactDays() != null) {
            w.append(" and (a.last_effective_contact_at is null or a.last_effective_contact_at < :effectiveCutoff)");
            params.addValue("effectiveCutoff",
                    timestamp(selection.now().minus(Duration.ofDays(c.noEffectiveContactDays()))));
        }
        if (c.pendingPromise() != null) {
            String has = "(a.status = 'PROMISE' or coalesce(a.resume_status, '') = 'PROMISE')";
            w.append(c.pendingPromise() ? " and " + has : " and not " + has);
        }
        if (c.activeAgreement() != null) {
            String has = "(a.status = 'AGREEMENT' or coalesce(a.resume_status, '') = 'AGREEMENT')";
            w.append(c.activeAgreement() ? " and " + has : " and not " + has);
        }
        if (c.minBrokenAgreements() != null) {
            w.append(" and a.broken_agreements >= :minBroken");
            params.addValue("minBroken", c.minBrokenAgreements());
        }
        if (c.minScore() != null) {
            w.append(" and a.score is not null and a.score >= :minScore");
            params.addValue("minScore", c.minScore());
        }
        if (c.maxScore() != null) {
            w.append(" and a.score is not null and a.score <= :maxScore");
            params.addValue("maxScore", c.maxScore());
        }
        if (!c.ownerTypes().isEmpty()) {
            w.append(" and a.owner_type in (:ownerTypes)");
            params.addValue("ownerTypes", c.ownerTypes());
        }
        return w.toString();
    }

    private static final RowMapper<AudienceSnapshot> MAPPER = (rs, i) -> new AudienceSnapshot(
            rs.getObject("case_id", UUID.class), rs.getObject("debtor_id", UUID.class),
            rs.getObject("creditor_id", UUID.class), rs.getObject("portfolio_id", UUID.class), rs.getString("status"),
            rs.getString("resume_status"), rs.getBoolean("eligible"), split(rs.getString("eligibility_reasons")),
            split(rs.getString("contactable_channels")), rs.getBigDecimal("open_balance"),
            rs.getBigDecimal("eligible_balance"), rs.getObject("oldest_due_date", java.time.LocalDate.class),
            rs.getString("owner_type"), rs.getInt("interaction_count"), rs.getInt("effective_contact_count"),
            instant(rs.getTimestamp("last_interaction_at")), instant(rs.getTimestamp("last_effective_contact_at")),
            (Integer) rs.getObject("score"), rs.getInt("broken_agreements"), rs.getLong("case_version"),
            instant(rs.getTimestamp("refreshed_at")));

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).filter(v -> !v.isBlank()).toList();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
