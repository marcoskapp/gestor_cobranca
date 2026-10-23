package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.compliance.application.port.in.HolidayUseCases;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases.TeamCommand;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.IntegrationEvent;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded.Participant;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.fail;

/**
 * Cenário comum dos testes de integração do módulo: assessoria provisionada (perfis padrão e
 * plano {@code PADRAO_MERCADO} semeado por evento), credores, negociadores X e Y numa equipe do
 * supervisor S, dois gestores e um financeiro. Os fatos chegam por eventos de integração
 * publicados em transação, como fariam os módulos produtores.
 */
@Import(IncentiveTestSupport.ClockConfig.class)
abstract class IncentiveTestSupport extends IntegrationTest {

    /** Relógio único e controlável para todos os testes de integração do módulo. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock incentiveTestClock() {
            return new MutableClock();
        }
    }

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Autowired
    MutableClock clock;
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
    CreditorUseCases creditors;
    @Autowired
    HolidayUseCases holidays;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolio = Ids.newId();
    UUID operatorX;
    UUID operatorY;
    UUID supervisor;
    UUID manager;
    UUID manager2;
    UUID finance;
    UUID team;
    UUID admin;

    /**
     * Provisiona a assessoria em 01/08/2026 (plano padrão vigente desde agosto) e devolve o relógio
     * à hora real, quando a carência de agosto e setembro já terminou.
     */
    void provisionTenant(String prefix) {
        clock.set(at(2026, 8, 1));
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.ProvisionedTenant provisioned = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> provisioning.provision(new ProvisionTenantCommand(slug, "Assessoria " + prefix + " Ltda", prefix,
                        "11.444.777/0001-61", "Admin", "admin@" + slug + ".test")));
        tenant = provisioned.tenant().id();
        admin = provisioned.adminUserId();
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operatorX = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR));
        operatorY = user("Operador Y", "y", roleIds.get(DefaultRoles.OPERADOR));
        supervisor = user("Supervisor S", "s", roleIds.get(DefaultRoles.SUPERVISOR));
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR));
        manager2 = user("Gestor H", "h", roleIds.get(DefaultRoles.GESTOR));
        finance = user("Financeiro F", "f", roleIds.get(DefaultRoles.FINANCEIRO));
        team = system(() -> teams.create(new TeamCommand("Equipe 1", null, supervisor, Set.of(operatorX, operatorY))).id());
        awaitTrue("plano padrão semeado", () -> count("select count(*) from incentive.commission_plan "
                + "where code = 'PADRAO_MERCADO' and status = 'PUBLISHED'") == 1);
        clock.reset();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private UUID user(String name, String key, UUID roleId) {
        return system(() -> userManagement.create(new CreateUserCommand(name, key + "-" + UUID.randomUUID().toString()
                .substring(0, 6) + "@teste.com", null, Set.of(roleId), true, Set.of())).id());
    }

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    <T> T as(UUID userId, String role, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                CreditorScope.ALL), action);
    }

    <T> T asScoped(UUID userId, String role, Set<UUID> creditorIds, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                CreditorScope.of(creditorIds)), action);
    }

    /** Publica o evento numa transação (como o produtor) e espera o consumo pelo módulo. */
    void publishAndAwait(IntegrationEvent event, String consumer) {
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
        awaitTrue("consumo de " + event.getClass().getSimpleName(), () -> {
            Long consumed = jdbcTemplate.queryForObject(
                    "select count(*) from shared.consumed_event where consumer = ? and event_id = ?", Long.class,
                    consumer, event.eventId());
            return consumed != null && consumed == 1;
        });
    }

    void publish(IntegrationEvent event) {
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
    }

    long count(String sql, Object... args) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        });
    }

    BigDecimal decimal(String sql, Object... args) {
        return system(() -> jdbcTemplate.queryForObject(sql, BigDecimal.class, args));
    }

    static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrompido aguardando " + what);
            }
        }
        fail("tempo esgotado aguardando " + what);
    }

    static Instant at(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 12, 0).atZone(ZONE).toInstant();
    }

    // ------------------------------------------------------------------ eventos

    AgreementAttributionRecorded attribution(UUID agreementId, UUID caseId, String origin, List<Participant> participants,
                                             String amount, Integer daysPastDue, UUID replaced, Instant formalizedAt) {
        return new AgreementAttributionRecorded(Ids.newId(), tenant, formalizedAt, agreementId, caseId, Ids.newId(),
                creditorA, portfolio, team, origin, "PHONE", participants, 1, new BigDecimal("40"), daysPastDue,
                new BigDecimal(amount), new BigDecimal(amount).multiply(new BigDecimal("1.25")),
                new BigDecimal(amount).multiply(new BigDecimal("0.25")), BigDecimal.ZERO, replaced, formalizedAt);
    }

    static Participant closer(UUID userId) {
        return new Participant("USER", userId, "CLOSER");
    }

    static Participant assistant(String role) {
        return new Participant("ASSISTANT", UUID.fromString("00000000-0000-0000-0000-0000000000a5"), role);
    }

    PaymentConfirmed payment(UUID paymentId, UUID agreementId, String amount, Instant paidAt) {
        return new PaymentConfirmed(Ids.newId(), tenant, paidAt, paymentId, Ids.newId(), creditorA, portfolio, agreementId,
                new BigDecimal(amount), paidAt, "PROVIDER", "PIX",
                List.of(new PaymentConfirmed.Allocation(Ids.newId(), Ids.newId(), new BigDecimal(amount))));
    }

    PaymentReversed reversal(UUID paymentId, UUID agreementId, String amount, Instant at) {
        return new PaymentReversed(Ids.newId(), tenant, at, Ids.newId(), paymentId, Ids.newId(), creditorA, portfolio,
                agreementId, new BigDecimal(amount), "chargeback");
    }
}
