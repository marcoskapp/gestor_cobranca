package br.com.gestorcobranca.communication;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.AccountView;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.SandboxUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.ApprovalCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.CreateTemplateCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateContent;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateView;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.IntegrationEvent;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * tabulações), credores A e B com carteiras ativas, operadores X e Y (todos os credores), operador
 * Z (somente credor B), supervisor S, política de contato sem restrição de horário (os testes não
 * dependem da hora em que rodam), conta WhatsApp sandbox padrão e template de cobrança aprovado.
 * Casos nascem como em produção: carga de títulos → {@code PortfolioLoaded} → sincronização.
 */
public abstract class CommunicationTestSupport extends IntegrationTest {

    protected static final List<String> ALL_DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
            "SATURDAY", "SUNDAY");
    protected static final String PHONE = "(11) 98765-4321";
    protected static final String PHONE_E164 = "+5511987654321";

    @Autowired
    protected ApplicationContext applicationContext;
    @Autowired
    protected TenantExecution tenantExecution;
    @Autowired
    protected TenantProvisioning provisioning;
    @Autowired
    protected RoleManagementUseCases roles;
    @Autowired
    protected UserManagementUseCases userManagement;
    @Autowired
    protected CreditorUseCases creditors;
    @Autowired
    protected ServiceContractUseCases contracts;
    @Autowired
    protected PortfolioUseCases portfolios;
    @Autowired
    protected DebtIngestionPort ingestion;
    @Autowired
    protected ContactPolicyUseCases policies;
    @Autowired
    protected ContactRestrictionUseCases restrictions;
    @Autowired
    protected CaseUseCases cases;
    @Autowired
    protected ChannelAccountUseCases channels;
    @Autowired
    protected TemplateUseCases templates;
    @Autowired
    protected MessageUseCases messages;
    @Autowired
    protected ConversationUseCases conversations;
    @Autowired
    protected SandboxUseCases sandbox;
    @Autowired
    protected ApplicationEventPublisher publisher;
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected UUID tenant;
    protected UUID creditorA;
    protected UUID creditorB;
    protected UUID portfolioA;
    protected UUID portfolioB;
    protected UUID operatorX;
    protected UUID operatorY;
    protected UUID operatorZ;
    protected UUID supervisor;
    protected AccountView whatsapp;

    protected void provision(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria " + prefix + " Ltda", prefix, "11.444.777/0001-61", "Admin",
                        "admin@" + slug + ".test"))).tenant().id();
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operatorX = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR), true, Set.of());
        operatorY = user("Operador Y", "y", roleIds.get(DefaultRoles.OPERADOR), true, Set.of());
        operatorZ = user("Operador Z", "z", roleIds.get(DefaultRoles.OPERADOR), false, Set.of(creditorB));
        supervisor = user("Supervisor S", "s", roleIds.get(DefaultRoles.SUPERVISOR), true, Set.of());
        awaitTrue("tabulações de mensagem", () -> count(
                "select count(*) from collection.tabulation where code in ('MESSAGE_SENT', 'MESSAGE_RECEIVED')") == 2);
        system(() -> policies.publish(new PublishPolicyCommand(null, null, "teste", List.of(
                new ChannelRuleInput("WHATSAPP", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("EMAIL", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("SMS", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("PHONE", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false)))));
        whatsapp = asAdmin(() -> channels.create(new CreateAccountCommand("WHATSAPP", "SANDBOX", null, "WhatsApp sandbox",
                "sandbox-number", null, null, Map.of())));
    }

    private UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            UUID id = portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
            portfolios.changeStatus(id, "ACTIVE", "Início da operação");
            return id;
        });
    }

    private UUID user(String name, String key, UUID roleId, boolean allCreditors, Set<UUID> creditorIds) {
        UUID id = system(() -> userManagement.create(new CreateUserCommand(name, key + "-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null, Set.of(roleId), allCreditors,
                creditorIds)).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", id));
        return id;
    }

    /** Devedor com celular (WhatsApp) e e-mail. */
    protected UUID debtor(String cpf) {
        return system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf, "Devedor " + cpf.substring(0, 3),
                LocalDate.of(1985, 3, 15), List.of(new ContactRecord("PHONE", PHONE, "MOBILE", true),
                new ContactRecord("EMAIL", "devedor" + cpf.substring(0, 3) + "@exemplo.com", null, false)))).id());
    }

    /** Carga de títulos seguida da sincronização de casos; devolve o caso ativo. */
    protected CaseView loadCase(UUID creditorId, UUID portfolioId, UUID debtorId, String principal) {
        UUID batch = Ids.newId();
        system(() -> {
            ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, debtorId,
                    "T-" + UUID.randomUUID().toString().substring(0, 8), null, 1, LocalDate.of(2026, 3, 10),
                    new BigDecimal(principal), null, null), batch);
            ingestion.completeImport(new ImportSummary(batch, creditorId, portfolioId, 1, 0, 1));
            return null;
        });
        awaitTrue("consumo do PortfolioLoaded", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and payload ->> 'importBatchId' = ?", batch.toString()) == 1);
        JobDrain.drain(applicationContext, jdbcTemplate, "collection.");
        return system(() -> cases.findActive(debtorId, portfolioId)).orElseThrow(() -> new AssertionError("sem caso"));
    }

    /** Template publicado (WhatsApp aprovado no provedor) com nome e valor. */
    protected TemplateView publishedTemplate(UUID creditorId, String code, String channel) {
        return asManager(() -> {
            TemplateView draft = templates.createDraft(new CreateTemplateCommand(creditorId, code, channel,
                    new TemplateContent("Cobrança", "COLLECTION", "EMAIL".equals(channel) ? "Aviso de débito" : null,
                            "Olá {{nome}}, há um débito de {{valor}}. Responda para negociar.",
                            List.of(new VariableInput("nome", "FIRST_NAME", true, null),
                                    new VariableInput("valor", "MONEY", true, null)), null, null)));
            if ("WHATSAPP".equals(channel)) {
                templates.registerApproval(draft.id(), new ApprovalCommand("APPROVED", code.toLowerCase(), "meta-123", null,
                        null));
            }
            return templates.publish(draft.id());
        });
    }

    protected void drainCommunication() {
        JobDrain.drain(applicationContext, jdbcTemplate, "communication.");
    }

    // ------------------------------------------------------------------ execução

    protected <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    protected <T> T as(UUID userId, String role, CreditorScope scope, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                scope), action);
    }

    protected <T> T asOperator(UUID userId, Supplier<T> action) {
        return as(userId, DefaultRoles.OPERADOR, userId.equals(operatorZ) ? CreditorScope.of(Set.of(creditorB))
                : CreditorScope.ALL, action);
    }

    protected <T> T asSupervisor(Supplier<T> action) {
        return as(supervisor, DefaultRoles.SUPERVISOR, CreditorScope.ALL, action);
    }

    protected <T> T asManager(Supplier<T> action) {
        return as(Ids.newId(), DefaultRoles.GESTOR, CreditorScope.ALL, action);
    }

    protected <T> T asAdmin(Supplier<T> action) {
        return as(Ids.newId(), DefaultRoles.ADMIN, CreditorScope.ALL, action);
    }

    /** Assistente com as permissões mínimas das ferramentas de conversa, restrito ao credor. */
    protected <T> T asAssistant(UUID policyId, UUID creditorId, Supplier<T> action) {
        return tenantExecution.callAs(Actor.assistant(tenant, policyId, "padrao",
                Set.of(Permission.MESSAGE_SEND, Permission.INBOX_VIEW, Permission.CASE_VIEW), Set.of(creditorId)), action);
    }

    protected void publishAndAwait(IntegrationEvent event, String consumer) {
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
        awaitTrue("consumo por " + consumer, () -> {
            Long consumed = jdbcTemplate.queryForObject(
                    "select count(*) from shared.consumed_event where consumer = ? and event_id = ?", Long.class,
                    consumer, event.eventId());
            return consumed != null && consumed == 1;
        });
    }

    /** Espera o consumo dos eventos já publicados do tenant pelos consumidores com o prefixo. */
    protected void awaitIdle(String listenerFragment) {
        awaitTrue("eventos pendentes de " + listenerFragment, () -> {
            Long pending = jdbcTemplate.queryForObject("""
                    select count(*) from event_publication
                     where completion_date is null and listener_id like ? and serialized_event like ?
                    """, Long.class, "%" + listenerFragment + "%", "%" + tenant + "%");
            return pending != null && pending == 0;
        });
    }

    protected long count(String sql, Object... args) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        });
    }

    protected static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 60_000;
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
}
