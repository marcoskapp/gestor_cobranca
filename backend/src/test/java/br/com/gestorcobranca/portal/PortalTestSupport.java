package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.ApprovalCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.CreateTemplateCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateContent;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateView;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
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
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.negotiation.domain.model.authority.StandardAuthorityProfiles;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CredentialsCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.ConfigurationCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.ConfigurationView;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases.AccessLinkView;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases.SendLinkCommand;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.fail;

/**
 * Cenário dos testes do portal: assessoria provisionada; credor A com portal habilitado só na
 * carteira A-01 (a A-02 fica fora), política de mercado publicada, WhatsApp sandbox, templates do
 * portal publicados e conta Pix sandbox; devedora Maria (títulos de 60 e 30 dias em A-01 e um título
 * em A-02) e devedor João (título em A-01), ambos com celular/WhatsApp; operador e gestor.
 */
abstract class PortalTestSupport extends IntegrationTest {

    static final String CPF_MARIA = "529.982.247-25";
    static final String CPF_JOAO = "111.444.777-35";
    /** CPF válido sem cadastro (anti-enumeração). */
    static final String CPF_UNKNOWN = "390.533.447-05";
    static final List<String> ALL_DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY",
            "SUNDAY");
    static final String NOTICE = "Aviso de privacidade: tratamos seus dados para a cobrança desta dívida, com base no "
            + "exercício regular de direitos e na proteção do crédito. Você pode pedir informações ao nosso encarregado.";
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final Pattern LINK_TOKEN = Pattern.compile("#([A-Za-z]{45})");

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    RoleManagementUseCases roles;
    @Autowired
    UserManagementUseCases userManagement;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    ServiceContractUseCases contracts;
    @Autowired
    PortfolioUseCases portfolios;
    @Autowired
    DebtIngestionPort ingestion;
    @Autowired
    CollectionPolicyUseCases policies;
    @Autowired
    ContactPolicyUseCases contactPolicies;
    @Autowired
    ChannelAccountUseCases channels;
    @Autowired
    TemplateUseCases templates;
    @Autowired
    MessageUseCases messages;
    @Autowired
    PixAccountUseCases pixAccounts;
    @Autowired
    PortalConfigurationUseCases portalConfigurations;
    @Autowired
    PortalOperationsUseCases portalOperations;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    JsonMapper json;
    @Autowired
    ApplicationContext applicationContext;
    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    UUID tenant;
    UUID admin;
    UUID operator;
    UUID manager;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioA2;
    UUID maria;
    UUID joao;
    LocalDate today;
    ConfigurationView portal;
    String slug;

    void provision(String prefix) {
        today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        String tenantSlug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.ProvisionedTenant provisioned = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> provisioning.provision(new ProvisionTenantCommand(tenantSlug, "Assessoria " + prefix + " Ltda", prefix,
                        "11.444.777/0001-61", "Admin", "admin@" + tenantSlug + ".test")));
        tenant = provisioned.tenant().id();
        admin = provisioned.adminUserId();
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioA2 = portfolio(creditorA, "A-02");
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operator = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR));
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR));
        maria = debtor(CPF_MARIA, "Maria Devedora", "(11) 98765-4321");
        joao = debtor(CPF_JOAO, "João Devedor", "(11) 97777-1234");
        UUID batchA = Ids.newId();
        UUID batchA2 = Ids.newId();
        system(() -> {
            ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, maria, "CT-0001-9876", null, 1,
                    today.minusDays(60), new BigDecimal("1000.00"), null, null), batchA);
            ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, maria, "CT-0002-5432", null, 2,
                    today.minusDays(30), new BigDecimal("500.00"), null, null), batchA);
            ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, joao, "CT-0003-1111", null, 1,
                    today.minusDays(45), new BigDecimal("800.00"), null, null), batchA);
            ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA2, maria, "CT-0004-2222", null, 1,
                    today.minusDays(20), new BigDecimal("300.00"), null, null), batchA2);
            ingestion.completeImport(new ImportSummary(batchA, creditorA, portfolioA, 3, 0, 2));
            ingestion.completeImport(new ImportSummary(batchA2, creditorA, portfolioA2, 1, 0, 1));
            return null;
        });
        awaitTrue("sincronização das carteiras agendada", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and tenant_id = ?", tenant) == 2);
        drainJobs("collection.");
        awaitTrue("casos abertos", () -> count("select count(*) from collection.collection_case where debtor_id in (?, ?)",
                maria, joao) == 3);
        awaitTrue("perfis de alçada semeados", () -> count(
                "select count(*) from negotiation.authority_profile where system_profile")
                == StandardAuthorityProfiles.all().size());
        asManager(() -> {
            UUID id = policies.createDraft(new CreatePolicyCommand(creditorA, "Política A", null,
                    StandardPolicyTerms.marketDefault())).id();
            return policies.publish(id, today).id();
        });
        system(() -> contactPolicies.publish(new PublishPolicyCommand(null, null, "teste", List.of(
                new ChannelRuleInput("WHATSAPP", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("EMAIL", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("SMS", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false)))));
        asAdmin(() -> channels.create(new ChannelAccountUseCases.CreateAccountCommand("WHATSAPP", "SANDBOX", null, "WhatsApp sandbox",
                "sandbox-number", null, null, Map.of())));
        template("PORTAL_CODIGO_WHATSAPP", "Seu código de acesso é {{codigo}}. Vale por {{minutos}} minutos.",
                List.of(new VariableInput("codigo", "CODE", true, null), new VariableInput("minutos", "INTEGER", true, null)));
        template("PORTAL_LINK_WHATSAPP", "Acesse o portal: {{link}} (vale por {{horas}} horas).",
                List.of(new VariableInput("link", "URL", true, null), new VariableInput("horas", "INTEGER", true, null)));
        template("PORTAL_ATENDIMENTO_WHATSAPP", "Recebemos seu pedido {{protocolo}}. Um atendente vai responder aqui.",
                List.of(new VariableInput("protocolo", "CODE", true, null)));
        asAdmin(() -> pixAccounts.create(new PixAccountUseCases.CreateAccountCommand(null, "PSP simulado", "SANDBOX", "pix@assessoria.test",
                "https://psp.sandbox.local/api/v2", "https://psp.sandbox.local/oauth/token", "HMAC", List.of(),
                new CredentialsCommand("client", "secret", "segredo-hmac-de-teste-com-32-caracteres-ou-mais", null, null))));
        slug = "banco-a-" + UUID.randomUUID().toString().substring(0, 6);
        portal = asAdmin(() -> portalConfigurations.create(configuration(slug, "NONE")));
    }

    ConfigurationCommand configuration(String portalSlug, String knowledge) {
        return new ConfigurationCommand(creditorA, portalSlug, true, "Banco A", "#1D4E89", "#0B6E4F",
                Set.of(portfolioA), Set.of("WHATSAPP"), knowledge, 48, NOTICE, "Atendimento de seg. a sex., 8h às 20h");
    }

    private void template(String code, String body, List<VariableInput> variables) {
        asManager(() -> {
            TemplateView draft = templates.createDraft(new CreateTemplateCommand(null, code, "WHATSAPP",
                    new TemplateContent("Portal", "SERVICE", null, body, variables, null, null)));
            templates.registerApproval(draft.id(), new ApprovalCommand("APPROVED", code.toLowerCase(), "meta-1", null, null));
            return templates.publish(draft.id());
        });
    }

    private UUID debtor(String cpf, String name, String phone) {
        return system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf, name, LocalDate.of(1985, 3, 15),
                List.of(new ContactRecord("PHONE", phone, "MOBILE", true)))).id());
    }

    UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            UUID id = portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
            return portfolios.changeStatus(id, "ACTIVE", null).id();
        });
    }

    private UUID user(String name, String key, UUID roleId) {
        UUID id = system(() -> userManagement.create(new CreateUserCommand(name, key + "-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null, Set.of(roleId), true, Set.of())).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", id));
        return id;
    }

    // ------------------------------------------------------------------ acesso

    /** Pede o código pelo portal e devolve o id do desafio. */
    UUID requestCode(String cpf) {
        Response r = post("/api/portal/v1/sites/" + slug + "/access/code", Map.of("document", cpf, "channel", "WHATSAPP"),
                null);
        if (r.status() != 202) {
            fail("pedido de código: " + r.status() + " " + r.body());
        }
        return UUID.fromString(r.json().get("challengeId").asString());
    }

    /** Código enviado ao devedor (lido da mensagem do communication, como o devedor o receberia). */
    String sentCode(UUID challengeId) {
        // o envio é assíncrono (fora da requisição): aguarda a mensagem ser registrada
        long deadline = System.currentTimeMillis() + 15_000;
        UUID messageId = null;
        while (messageId == null && System.currentTimeMillis() < deadline) {
            messageId = system(() -> jdbcTemplate.queryForObject(
                    "select message_id from portal.access_challenge where id = ?", UUID.class, challengeId));
            if (messageId == null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (messageId == null) {
            fail("código não enviado para o desafio " + challengeId);
        }
        UUID sentMessage = messageId;
        String content = asAdmin(() -> messages.get(sentMessage)).content();
        Matcher m = CODE.matcher(content);
        if (!m.find()) {
            fail("mensagem sem código: " + content);
        }
        return m.group(1);
    }

    Response verify(UUID challengeId, String code, String knowledge) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", code);
        body.put("knowledgeAnswer", knowledge);
        return post("/api/portal/v1/sites/" + slug + "/access/challenges/" + challengeId + "/verify", body, null);
    }

    /** Acesso completo por documento + código; devolve o token do portal. */
    String login(String cpf) {
        UUID challenge = requestCode(cpf);
        Response r = verify(challenge, sentCode(challenge), null);
        if (r.status() != 200) {
            fail("verificação: " + r.status() + " " + r.body());
        }
        return r.json().get("accessToken").asString();
    }

    /** Login e reconhecimento do aviso de privacidade. */
    String loginReady(String cpf) {
        String token = login(cpf);
        Response ack = post("/api/portal/v1/me/session/privacy", Map.of("noticeVersion", 1), token);
        if (ack.status() != 200) {
            fail("aviso: " + ack.status() + " " + ack.body());
        }
        return token;
    }

    AccessLinkView sendLink(UUID debtorId) {
        return asOperator(() -> portalOperations.sendAccessLink(new SendLinkCommand(debtorId, creditorA, "WHATSAPP")));
    }

    String linkToken(AccessLinkView link) {
        String content = asAdmin(() -> messages.get(link.messageId())).content();
        Matcher m = LINK_TOKEN.matcher(content);
        if (!m.find()) {
            fail("mensagem sem link: " + content);
        }
        return m.group(1);
    }

    // ------------------------------------------------------------------ HTTP

    record Response(int status, String body, JsonNode json) {
    }

    Response get(String path, String token) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), token);
    }

    Response post(String path, Object body, String token) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))), token);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    Response send(HttpRequest.Builder builder, String token) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.header("User-Agent", "PortalIT/1.0");
        try {
            HttpResponse<String> r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String body = r.body();
            JsonNode node = body == null || body.isBlank() || !(body.startsWith("{") || body.startsWith("["))
                    ? null : json.readTree(body);
            return new Response(r.statusCode(), body, node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ execução

    void drainJobs(String typePrefix) {
        JobDrain.drain(applicationContext, jdbcTemplate, typePrefix);
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

    <T> T asAdmin(Supplier<T> action) {
        return as(admin, DefaultRoles.ADMIN, action);
    }

    <T> T asManager(Supplier<T> action) {
        return as(manager, DefaultRoles.GESTOR, action);
    }

    <T> T asOperator(Supplier<T> action) {
        return as(operator, DefaultRoles.OPERADOR, action);
    }

    /** Ator devedor como o resolvedor do token do portal o monta (para testar a defesa em profundidade). */
    <T> T asDebtor(UUID debtorId, Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.DEBTOR, debtorId, tenant, Ids.newId(), "portal:debtor",
                Set.of(Permission.NEGOTIATION_SIMULATE, Permission.NEGOTIATION_PROPOSE, Permission.AGREEMENT_VIEW,
                        Permission.AGREEMENT_FORMALIZE, Permission.PAYMENT_INSTRUCTION_CREATE, Permission.PAYMENT_VIEW),
                CreditorScope.of(Set.of(creditorA))), action);
    }

    long count(String sql, Object... args) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        });
    }

    static void awaitTrue(String what, BooleanSupplier condition) {
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
