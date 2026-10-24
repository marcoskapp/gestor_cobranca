package br.com.gestorcobranca.analytics;

import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases.TeamCommand;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Base dos testes de integração do analytics: assessoria provisionada (perfis padrão), operadores
 * X e Y na equipe do supervisor S, gestor G, e um construtor de fatos que grava diretamente nas
 * tabelas dos módulos produtores (como app_runtime, sob o RLS da assessoria) — o analytics lê o
 * banco por views, então o teste exercita exatamente as colunas do contrato.
 */
abstract class AnalyticsTestSupport extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final UUID ASSISTANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    RoleManagementUseCases roles;
    @Autowired
    UserManagementUseCases userManagement;
    @Autowired
    TeamUseCases teams;
    @Autowired
    JdbcTemplate jdbc;

    UUID tenant;
    UUID operatorX;
    UUID operatorY;
    UUID supervisor;
    UUID manager;
    UUID team;

    void provisionTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.ProvisionedTenant provisioned = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> provisioning.provision(new ProvisionTenantCommand(slug, "Assessoria " + prefix + " Ltda", prefix,
                        "11.444.777/0001-61", "Admin", "admin@" + slug + ".test")));
        tenant = provisioned.tenant().id();
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operatorX = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR));
        operatorY = user("Operador Y", "y", roleIds.get(DefaultRoles.OPERADOR));
        supervisor = user("Supervisor S", "s", roleIds.get(DefaultRoles.SUPERVISOR));
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR));
        team = system(() -> teams.create(new TeamCommand("Equipe Varejo", null, supervisor,
                Set.of(operatorX, operatorY))).id());
    }

    private UUID user(String name, String key, UUID roleId) {
        return system(() -> userManagement.create(new CreateUserCommand(name, key + "-" + UUID.randomUUID().toString()
                .substring(0, 6) + "@teste.com", null, Set.of(roleId), true, Set.of())).id());
    }

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    void run(Runnable action) {
        tenantExecution.inTenant(tenant, "test", action);
    }

    <T> T as(UUID userId, String role, Supplier<T> action) {
        return asScoped(userId, role, null, action);
    }

    <T> T asScoped(UUID userId, String role, Set<UUID> creditorIds, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return asWith(userId, permissions, creditorIds, action);
    }

    <T> T asWith(UUID userId, Set<Permission> permissions, Set<UUID> creditorIds, Supplier<T> action) {
        CreditorScope scope = creditorIds == null ? CreditorScope.ALL : CreditorScope.of(creditorIds);
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "TEST",
                permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions), scope), action);
    }

    long count(String sql, Object... args) {
        return system(() -> {
            Long value = jdbc.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        });
    }

    static Instant at(int year, int month, int day, int hour) {
        return LocalDateTime.of(year, month, day, hour, 0).atZone(ZONE).toInstant();
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    // ===================================================================== fatos

    /** Grava fatos dos módulos produtores na assessoria corrente (chamar dentro de {@link #run}). */
    final class Facts {

        final UUID policy;
        final UUID profile;
        final UUID tabulationCpc;
        final UUID tabulationNoAnswer;

        Facts() {
            Instant now = Instant.now();
            policy = Ids.newId();
            profile = Ids.newId();
            tabulationCpc = Ids.newId();
            tabulationNoAnswer = Ids.newId();
            UUID anyCreditor = creditor("Credor da política", "POL");
            jdbc.update("""
                    insert into negotiation.collection_policy (id, tenant_id, version, created_at, updated_at, creditor_id,
                        policy_version, name, status, terms, terms_schema_version)
                    values (?, ?, 0, ?, ?, ?, 1, 'Política de teste', 'DRAFT', '{}'::jsonb, 1)""",
                    policy, tenant, ts(now), ts(now), anyCreditor);
            jdbc.update("""
                    insert into negotiation.authority_profile (id, tenant_id, version, created_at, updated_at, code, name,
                        system_profile, max_concession_level, max_discount_share, max_installments,
                        min_down_payment_share, can_formalize, can_approve, max_renegotiations)
                    values (?, ?, 0, ?, ?, 'TESTE_ANALYTICS', 'Teste', false, 3, 50, 12, 0, true, false, 2)""",
                    profile, tenant, ts(now), ts(now));
            for (Object[] t : new Object[][]{{tabulationCpc, "CPC_TESTE", true}, {tabulationNoAnswer, "NA_TESTE", false}}) {
                jdbc.update("""
                        insert into collection.tabulation (id, tenant_id, version, created_at, updated_at, code, name,
                            effective_contact, promise, callback, requires_note, display_order, active)
                        values (?, ?, 0, ?, ?, ?, ?, ?, false, false, false, 99, true)""",
                        t[0], tenant, ts(now), ts(now), t[1], t[1], t[2]);
            }
        }

        UUID creditor(String tradeName, String doc) {
            UUID id = Ids.newId();
            Instant now = Instant.now();
            String document = String.format("%014d", Math.abs((tradeName + doc + id).hashCode()));
            jdbc.update("""
                    insert into organization.creditor (id, tenant_id, version, created_at, updated_at, legal_name,
                        trade_name, document, status) values (?, ?, 0, ?, ?, ?, ?, ?, 'ACTIVE')""",
                    id, tenant, ts(now), ts(now), tradeName + " S.A.", tradeName, document);
            return id;
        }

        UUID portfolio(UUID creditorId, String code) {
            UUID contract = Ids.newId();
            UUID id = Ids.newId();
            Instant now = Instant.now();
            jdbc.update("""
                    insert into organization.service_contract (id, tenant_id, version, created_at, updated_at, creditor_id,
                        number, start_date, settlement_day, status)
                    values (?, ?, 0, ?, ?, ?, ?, date '2026-01-01', 5, 'ACTIVE')""",
                    contract, tenant, ts(now), ts(now), creditorId, "K-" + code);
            jdbc.update("""
                    insert into organization.portfolio (id, tenant_id, version, created_at, updated_at, creditor_id,
                        contract_id, code, name, received_on, status)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, date '2026-01-01', 'ACTIVE')""",
                    id, tenant, ts(now), ts(now), creditorId, contract, code, "Carteira " + code);
            return id;
        }

        UUID debtor() {
            UUID id = Ids.newId();
            Instant now = Instant.now();
            jdbc.update("""
                    insert into debt.debtor (id, tenant_id, version, created_at, updated_at, person_type, name,
                        document_encrypted, document_hash, status)
                    values (?, ?, 0, ?, ?, 'INDIVIDUAL', 'Devedor teste', 'cifrado', ?, 'ACTIVE')""",
                    id, tenant, ts(now), ts(now), id.toString().replace("-", ""));
            return id;
        }

        void title(UUID creditorId, UUID portfolioId, UUID debtorId, LocalDate due, String status, String balance) {
            Instant now = Instant.now();
            BigDecimal open = new BigDecimal(balance);
            jdbc.update("""
                    insert into debt.debt_title (id, tenant_id, version, created_at, updated_at, creditor_id, portfolio_id,
                        debtor_id, external_id, due_date, principal_amount, status, dispute_blocked, open_balance, paid_total)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, 0)""",
                    Ids.newId(), tenant, ts(now), ts(now), creditorId, portfolioId, debtorId,
                    "T-" + UUID.randomUUID(), due, open.signum() > 0 ? open : new BigDecimal("100.00"), status, open);
        }

        UUID queue(String code) {
            UUID id = Ids.newId();
            Instant now = Instant.now();
            jdbc.update("""
                    insert into collection.queue (id, tenant_id, version, created_at, updated_at, code, name, active,
                        priority, ordering, strategy)
                    values (?, ?, 0, ?, ?, ?, ?, true, 10, 'OLDEST_OVERDUE', 'PULL')""",
                    id, tenant, ts(now), ts(now), code, "Fila " + code);
            return id;
        }

        UUID collectionCase(UUID debtorId, UUID creditorId, UUID portfolioId, String status, boolean eligible,
                            String open, String eligibleBalance, String ownerType, UUID queueId, UUID assignee,
                            LocalDate oldestDue) {
            UUID id = Ids.newId();
            Instant now = Instant.now();
            boolean closed = status.equals("CLOSED");
            jdbc.update("""
                    insert into collection.collection_case (id, tenant_id, version, created_at, updated_at, debtor_id,
                        creditor_id, portfolio_id, status, opened_at, closed_at, close_reason, open_balance,
                        eligible_balance, open_titles, suspended_titles, disputed_titles, oldest_due_date,
                        balance_updated_at, eligible, eligibility_reasons, queue_id, owner_type, assignee_user_id,
                        interaction_count, effective_contact_count)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 0, ?, ?, ?, ?, ?, ?, ?, 0, 0)""",
                    id, tenant, ts(now), ts(now), debtorId, creditorId, portfolioId, status, ts(now),
                    closed ? ts(now) : null, closed ? "SETTLED" : null, new BigDecimal(open),
                    new BigDecimal(eligibleBalance), oldestDue, ts(now), eligible, eligible ? null : "SUSPENDED",
                    queueId, ownerType, assignee);
            return id;
        }

        void interaction(UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, String channel, boolean effective,
                         UUID operatorId, String actorType, Instant at) {
            Instant now = Instant.now();
            jdbc.update("""
                    insert into collection.interaction (id, tenant_id, version, created_at, updated_at, case_id, debtor_id,
                        creditor_id, portfolio_id, channel, direction, tabulation_id, outcome_code, effective_contact,
                        started_at, operator_id, actor_type, source)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, 'OUTBOUND', ?, ?, ?, ?, ?, ?, 'MANUAL')""",
                    Ids.newId(), tenant, ts(now), ts(now), caseId, debtorId, creditorId, portfolioId, channel,
                    effective ? tabulationCpc : tabulationNoAnswer, effective ? "CPC_TESTE" : "NA_TESTE", effective,
                    ts(at), operatorId, actorType);
        }

        UUID simulation(UUID creditorId, UUID debtorId, UUID portfolioId, String actorType, UUID actorId) {
            UUID id = Ids.newId();
            Instant now = Instant.now();
            jdbc.update("""
                    insert into negotiation.simulation (id, tenant_id, version, created_at, updated_at, creditor_id,
                        debtor_id, portfolio_id, policy_id, policy_version, reference_date, actor_type, actor_id,
                        authority_profile_id, authority_profile_code, requested_level, granted_level, title_count,
                        max_days_overdue, updated_amount, calculation, engine_version)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, 1, date '2026-06-01', ?, ?, ?, 'TESTE_ANALYTICS', 0, 0, 1, 30,
                            1000, '{}'::jsonb, 1)""",
                    id, tenant, ts(now), ts(now), creditorId, debtorId, portfolioId, policy, actorType, actorId, profile);
            return id;
        }

        /** Proposta criada em {@code createdAt}; status FORMALIZED (com aceite) ou outro informado. */
        UUID proposal(UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId, String actorType, UUID actorId,
                      String channel, String status, Instant createdAt) {
            UUID simulationId = simulation(creditorId, debtorId, portfolioId, actorType, actorId);
            UUID id = Ids.newId();
            boolean accepted = status.equals("FORMALIZED") || status.equals("ACCEPTED");
            jdbc.update("""
                    insert into negotiation.proposal (id, tenant_id, version, created_at, updated_at, creditor_id,
                        debtor_id, portfolio_id, case_id, simulation_id, policy_id, policy_version, reference_date,
                        actor_type, actor_id, authority_profile_code, concession_level, copilot, channel, discount_rates,
                        installments, first_due_date, updated_amount, discount_amount, negotiated_amount, down_payment,
                        financing_interest, total_amount, envelope_usage, max_days_overdue, terms_fingerprint,
                        required_discount_share, down_payment_share, authority_violations, calculation, status,
                        valid_until, renegotiation_number, decided_by, accepted_at, term_sha256)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, 1, date '2026-06-01', ?, ?, 'TESTE_ANALYTICS', 0, false, ?,
                            '{}'::jsonb, 1, date '2026-07-01', 1000, 0, 1000, 100, 0, 1000, 0, 30, 'fp', 0, 0.1,
                            '[]'::jsonb, '{}'::jsonb, ?, date '2026-12-31', 0, ?, ?, ?)""",
                    id, tenant, ts(createdAt), ts(createdAt), creditorId, debtorId, portfolioId, caseId, simulationId,
                    policy, actorType, actorId, channel, status, status.equals("REJECTED") ? manager : null,
                    accepted ? ts(createdAt) : null, accepted ? "a".repeat(64) : null);
            return id;
        }

        /** Acordo formalizado em {@code formalizedAt}, com a proposta (FORMALIZED) criada uma hora antes. */
        UUID agreement(UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId, String origin, String closerType,
                       UUID closerId, String channel, Instant formalizedAt, String total, int installments, String status,
                       Instant endedAt, UUID replaced) {
            String proposalActorType = closerType.equals("ASSISTANT") ? "ASSISTANT" : "USER";
            UUID proposalActor = closerType.equals("USER") || closerType.equals("ASSISTANT") ? closerId : operatorX;
            UUID proposalId = proposal(creditorId, debtorId, portfolioId, caseId, proposalActorType, proposalActor,
                    channel, "FORMALIZED", formalizedAt.minusSeconds(3600));
            UUID simulationId = jdbc.queryForObject("select simulation_id from negotiation.proposal where id = ?",
                    UUID.class, proposalId);
            UUID id = Ids.newId();
            BigDecimal totalAmount = new BigDecimal(total);
            jdbc.update("""
                    insert into negotiation.agreement (id, tenant_id, version, created_at, updated_at, proposal_id,
                        simulation_id, creditor_id, debtor_id, portfolio_id, case_id, policy_id, policy_version,
                        reference_date, concession_level, envelope_usage, max_days_overdue, channel, origin,
                        originator_type, originator_id, closer_type, closer_id, updated_amount, discount_amount,
                        negotiated_amount, financing_interest, total_amount, down_payment, paid_amount,
                        installment_count, break_overdue_installments, break_tolerance_days, status, formalized_at,
                        ended_at, replaced_agreement_id, renegotiation_number, incremental_amount, accepted_at,
                        term_sha256)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, date '2026-06-01', 0, 0, 30, ?, ?, ?, ?, ?, ?, ?, 0, ?,
                            0, ?, ?, 0, ?, 2, 5, ?, ?, ?, ?, ?, 0, ?, ?)""",
                    id, tenant, ts(formalizedAt), ts(formalizedAt), proposalId, simulationId, creditorId, debtorId,
                    portfolioId, caseId, policy, channel, origin, proposalActorType, proposalActor, closerType,
                    closerId, totalAmount, totalAmount, totalAmount, totalAmount.divide(BigDecimal.valueOf(installments),
                            2, java.math.RoundingMode.HALF_EVEN), installments, status, ts(formalizedAt), ts(endedAt),
                    replaced, replaced == null ? 0 : 1, ts(formalizedAt), "b".repeat(64));
            return id;
        }

        void installment(UUID agreementId, int number, LocalDate due, String amount, String status, Instant paidAt) {
            Instant now = Instant.now();
            BigDecimal value = new BigDecimal(amount);
            jdbc.update("""
                    insert into negotiation.agreement_installment (id, tenant_id, version, created_at, updated_at,
                        agreement_id, number, due_date, amount, paid_amount, status, paid_at)
                    values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Ids.newId(), tenant, ts(now), ts(now), agreementId, number, due, value,
                    status.equals("PAID") ? value : BigDecimal.ZERO, status, ts(paidAt));
        }

        /** Recebimento (PAYMENT) na data informada; devolve o id para estorno. */
        UUID receipt(UUID creditorId, UUID portfolioId, UUID agreementId, String amount, LocalDate on, Integer daysPastDue) {
            UUID id = Ids.newId();
            Instant now = Instant.now();
            jdbc.update("""
                    insert into settlement.receipt (id, tenant_id, version, created_at, updated_at, kind, allocation_id,
                        payment_id, creditor_id, portfolio_id, agreement_id, amount, honorarium_amount, days_past_due,
                        payment_type, received_by, renegotiation, paid_on, competence_date, detail, rating_status)
                    values (?, ?, 0, ?, ?, 'PAYMENT', ?, ?, ?, ?, ?, ?, 0, ?, 'INSTALLMENTS', 'AGENCY', false, ?, ?,
                            '{}'::jsonb, 'PENDING_RULES')""",
                    id, tenant, ts(now), ts(now), Ids.newId(), Ids.newId(), creditorId, portfolioId, agreementId,
                    new BigDecimal(amount), daysPastDue, on, on);
            return id;
        }

        void reversal(UUID receiptId, String amount, LocalDate on) {
            Instant now = Instant.now();
            jdbc.update("""
                    insert into settlement.receipt (id, tenant_id, version, created_at, updated_at, kind, allocation_id,
                        payment_id, reversal_id, reverses_receipt_id, reversal_reason, creditor_id, portfolio_id,
                        agreement_id, amount, honorarium_amount, days_past_due, payment_type, received_by, renegotiation,
                        paid_on, competence_date, detail, rating_status)
                    select ?, tenant_id, 0, ?, ?, 'REVERSAL', allocation_id, payment_id, ?, id, 'CHARGEBACK',
                           creditor_id, portfolio_id, agreement_id, ?, 0, days_past_due, payment_type, received_by,
                           renegotiation, paid_on, ?, '{}'::jsonb, 'PENDING_RULES'
                    from settlement.receipt where id = ?""",
                    Ids.newId(), ts(now), ts(now), Ids.newId(), new BigDecimal(amount).negate(), on, receiptId);
        }

        void caseSnapshot(UUID caseId, UUID creditorId, UUID portfolioId, UUID assignee, Instant at, String eligible) {
            jdbc.update("""
                    insert into incentive.case_snapshot (tenant_id, source_event_id, case_id, effective_at, creditor_id,
                        portfolio_id, assignee_id, team_id, status, eligible, eligible_balance)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', true, ?)""",
                    tenant, Ids.newId(), caseId, ts(at), creditorId, portfolioId, assignee, team, new BigDecimal(eligible));
        }

        void performance(UUID userId, LocalDate date, UUID creditorId, UUID portfolioId, String channel, int attempts,
                         int effective, int answered, long talkSeconds) {
            jdbc.update("""
                    insert into incentive.performance_fact (tenant_id, fact_date, user_id, creditor_id, portfolio_id,
                        delay_bucket, channel, attempts, effective_contacts, answered_calls, talk_seconds)
                    values (?, ?, ?, ?, ?, 'D31_90', ?, ?, ?, ?, ?)""",
                    tenant, date, userId, creditorId, portfolioId, channel, attempts, effective, answered, talkSeconds);
        }

        void workedCase(UUID userId, LocalDate date, UUID caseId, UUID creditorId, UUID portfolioId) {
            jdbc.update("""
                    insert into incentive.worked_case_day (tenant_id, fact_date, user_id, case_id, creditor_id,
                        portfolio_id, channel) values (?, ?, ?, ?, ?, ?, 'PHONE')""",
                    tenant, date, userId, caseId, creditorId, portfolioId);
        }

        void loggedTime(UUID userId, LocalDate date, long seconds) {
            jdbc.update("insert into incentive.logged_time_daily (tenant_id, fact_date, user_id, seconds) values (?, ?, ?, ?)",
                    tenant, date, userId, seconds);
        }

        void instruction(UUID creditorId, UUID debtorId, String status, String failureCode, Instant at) {
            jdbc.update("""
                    insert into payment.payment_instruction (id, tenant_id, version, created_at, updated_at, type, status,
                        creditor_id, debtor_id, title_id, amount, expires_at, failure_code)
                    values (?, ?, 0, ?, ?, 'PIX_IMMEDIATE', ?, ?, ?, ?, 100, ?, ?)""",
                    Ids.newId(), tenant, ts(at), ts(at), status, creditorId, debtorId, Ids.newId(),
                    ts(at.plusSeconds(3600)), failureCode);
        }

        void divergence(UUID creditorId, String type, String status, Instant created, Instant resolved) {
            jdbc.update("""
                    insert into payment.divergence (id, tenant_id, version, created_at, updated_at, type, source, status,
                        creditor_id, amount, occurred_on, resolution, resolved_at)
                    values (?, ?, 0, ?, ?, ?, 'STATEMENT', ?, ?, 50, ?, ?, ?)""",
                    Ids.newId(), tenant, ts(created), ts(created), type, status, creditorId,
                    LocalDate.ofInstant(created, ZONE), resolved == null ? null : "ACCEPTED", ts(resolved));
        }

        void importBatch(UUID creditorId, UUID portfolioId, String status, Instant started, long seconds, int rows,
                         int rowsWithError) {
            jdbc.update("""
                    insert into ingestion.import_batch (id, tenant_id, version, created_at, updated_at, creditor_id,
                        portfolio_id, layout_code, layout_version, source, mode, status, file_key, file_name, file_size,
                        file_sha256, forced, rows_read, rows_valid, rows_with_error, errors, warnings, debtors_created,
                        debtors_updated, titles_created, titles_updated, titles_unchanged, titles_skipped,
                        titles_suspended, principal_total, issues_recorded, last_processed_row, total_rows, started_at, finished_at)
                    values (?, ?, 0, ?, ?, ?, ?, 'PADRAO', 1, 'CSV', 'IMPORT', ?, 'k', 'f.csv', 10, ?, false, ?, ?, ?, ?,
                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?, ?)""",
                    Ids.newId(), tenant, ts(started), ts(started), creditorId, portfolioId, status, "c".repeat(64), rows,
                    rows - rowsWithError, rowsWithError, rowsWithError, rows, ts(started), ts(started.plusSeconds(seconds)));
        }

        void deadJob(UUID tenantId, String type) {
            Instant now = Instant.now();
            jdbc.update("""
                    insert into shared.processing_job (id, tenant_id, type, status, run_at, attempts, created_at, finished_at)
                    values (?, ?, ?, 'DEAD', ?, 5, ?, ?)""", Ids.newId(), tenantId, type, ts(now), ts(now), ts(now));
        }
    }
}
