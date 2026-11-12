package br.com.gestorcobranca.compliance;

import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases.ProcessorDetails;
import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases.RecipientInUseView;
import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases.RegisterProcessorCommand;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestView;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectResponseDraftUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectResponseDraftUseCases.AlertView;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectResponseDraftUseCases.RecipientView;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectResponseDraftUseCases.ResponseDraftView;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases.RegisterLegalBasisCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rascunho de resposta ao titular (art. 18, I e VII) contra as fontes reais: credores (debt), mensagens
 * (communication), chamadas (telephony) e escritório parceiro (legal), gravados nas tabelas dos módulos sob a RLS da
 * assessoria. Confere agregação por provedor, base legal, cadastro de operadores, isolamento de outro devedor e de
 * outra assessoria, permissão e escopo (403 sem vazamento), log de acesso e auditoria do uso do rascunho.
 */
class DataSubjectResponseDraftIT extends IntegrationTest {

    private static final String CPF = "529.982.247-25";
    private static final String PHONE = "(11) 98765-4321";
    private static final String EMAIL = "maria.titular@exemplo.com";

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    ServiceContractUseCases contracts;
    @Autowired
    PortfolioUseCases portfolios;
    @Autowired
    DebtIngestionPort ingestion;
    @Autowired
    DataSubjectRequestUseCases requests;
    @Autowired
    DataSubjectResponseDraftUseCases drafts;
    @Autowired
    DataProcessorUseCases processors;
    @Autowired
    LegalBasisUseCases legalBases;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WebApplicationContext webContext;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID debtor;
    UUID otherDebtor;
    UUID lawFirm;
    UUID latestConversation;
    UUID pixAccount;

    @BeforeEach
    void setUp() {
        String slug = "dsrd-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Rascunho Ltda", "Rascunho", "11.444.777/0001-61"))
                .id());
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco Fictício S.A.", "Banco Fictício",
                "11.222.333/0001-81", null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja Exemplo S.A.", "Loja Exemplo",
                "45.997.418/0001-53", null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        UUID portfolioB = portfolio(creditorB, "B-01");
        debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord(CPF, "Maria Titular", LocalDate.of(1985, 3, 15),
                List.of(new ContactRecord("PHONE", PHONE, "MOBILE", true),
                        new ContactRecord("EMAIL", EMAIL, null, false)), null)).id());
        otherDebtor = system(() -> ingestion.upsertDebtor(new DebtorRecord("111.444.777-35", "João Outro", null,
                List.of(), null)).id());
        title(creditorA, portfolioA, debtor, "A-1");
        title(creditorB, portfolioB, debtor, "B-1");
        title(creditorA, portfolioA, otherDebtor, "A-9");
        system(() -> legalBases.register(new RegisterLegalBasisCommand(creditorA, null, "EXERCICIO_REGULAR_DIREITOS",
                null, "CT-2026-01", LocalDate.of(2026, 1, 1), null, "Jurídico fictício", 60, null)));

        // mensagens: duas pelo WhatsApp (contas diferentes, mesmo provedor) para a titular; e-mail só para o outro devedor
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID whatsapp1 = channelAccount("WHATSAPP", "WHATSAPP_CLOUD", "WhatsApp cobrança", null);
        UUID whatsapp2 = channelAccount("WHATSAPP", "WHATSAPP_CLOUD", "WhatsApp Loja", creditorB);
        UUID smtp = channelAccount("EMAIL", "SMTP", "E-mail", null);
        UUID c1 = conversation(debtor, creditorA, "WHATSAPP", whatsapp1, base);
        message(c1, "WHATSAPP", whatsapp1, base);
        latestConversation = conversation(debtor, creditorB, "WHATSAPP", whatsapp2, base.plus(2, ChronoUnit.DAYS));
        message(latestConversation, "WHATSAPP", whatsapp2, base.plus(2, ChronoUnit.DAYS));
        UUID other = conversation(otherDebtor, creditorA, "EMAIL", smtp, base);
        message(other, "EMAIL", smtp, base);
        // mensagem ainda na fila (não passou pelo provedor): não conta
        message(c1, "WHATSAPP", whatsapp1, base.plus(5, ChronoUnit.DAYS), false);

        // Pix: uma cobrança registrada no PSP conta; a cancelada antes do registro e a encerrada sem chamar o PSP
        // (conta inativa) não saíram da assessoria
        pixAccount = pixAccount("PSP Fictício");
        instruction(pixAccount, debtor, creditorA, "ACTIVE", base.plus(1, ChronoUnit.DAYS), true, null);
        instruction(pixAccount, debtor, creditorA, "CANCELLED", base.plus(2, ChronoUnit.DAYS), false, null);
        instruction(pixAccount, debtor, creditorA, "FAILED", base.plus(3, ChronoUnit.DAYS), false, "PIX_ACCOUNT_INACTIVE");

        // telefonia: uma chamada que chegou à plataforma e uma recusada antes de discar (não conta)
        UUID twilio = providerAccount("TWILIO", "Twilio principal");
        UUID caseA = collectionCase(debtor, creditorA, portfolioA);
        call(twilio, "TWILIO", debtor, creditorA, caseA, base.plus(1, ChronoUnit.DAYS), true);
        call(twilio, "TWILIO", debtor, creditorA, caseA, base.plus(3, ChronoUnit.DAYS), false);

        // jurídico: caso com escritório parceiro
        lawFirm = lawFirm("Advocacia Fictícia Associados");
        legalMatter(caseA, debtor, creditorA, portfolioA, lawFirm, base.plus(4, ChronoUnit.DAYS));
    }

    @Test
    void sharingDraftGathersRealSourcesAppliesRegistryAndAuditsUse() throws Exception {
        RequestView request = verified("INFORMATION_SHARING", CPF);

        ResponseDraftView draft = asOfficer(() -> drafts.draft(request.id()));
        assertThat(draft.processing()).isTrue();
        assertThat(draft.unavailable()).isEmpty();
        assertThat(draft.recipients()).extracting(RecipientView::key).containsExactly(
                "debt.creditor:" + creditorA, "debt.creditor:" + creditorB, "communication.whatsapp_cloud",
                "telephony.twilio", "payment.pix_account:" + pixAccount, "legal.law_firm:" + lawFirm);
        assertThat(recipient(draft, "payment.pix_account:" + pixAccount).count()).isEqualTo(1);
        RecipientView whatsapp = recipient(draft, "communication.whatsapp_cloud");
        assertThat(whatsapp.count()).isEqualTo(2);
        assertThat(whatsapp.countLabel()).isEqualTo("2 mensagens");
        assertThat(whatsapp.links()).singleElement().satisfies(l -> {
            assertThat(l.route()).isEqualTo("/conversas/" + latestConversation);
            assertThat(l.anyPermission()).containsExactly("INBOX_VIEW");
        });
        RecipientView telephony = recipient(draft, "telephony.twilio");
        assertThat(telephony.count()).isEqualTo(1);
        assertThat(telephony.links().get(0).queryParams()).containsEntry("devedor", debtor.toString());
        assertThat(recipient(draft, "legal.law_firm:" + lawFirm).name()).isEqualTo("Advocacia Fictícia Associados");
        assertThat(recipient(draft, "debt.creditor:" + creditorA).legalBases())
                .containsExactly("exercício regular de direitos (art. 7º, VI)");
        // o e-mail é só do outro devedor
        assertThat(draft.recipients()).noneMatch(r -> r.key().equals("communication.smtp"));
        assertThat(draft.alerts()).extracting(AlertView::code)
                .contains("CREDITOR_WITHOUT_LEGAL_BASIS", "PROCESSOR_UNREGISTERED");
        assertThat(draft.alerts()).filteredOn(a -> a.code().equals("CREDITOR_WITHOUT_LEGAL_BASIS"))
                .singleElement().satisfies(a -> assertThat(a.recipientId()).isEqualTo(creditorB));
        assertThat(draft.text()).contains("Olá, Maria.", "Banco Fictício", "Loja Exemplo", "Twilio",
                "Advocacia Fictícia Associados").doesNotContain(PHONE, EMAIL, "529.982", "52998224725");

        // cadastro do operador: nome, finalidade e país entram no texto; o alerta some
        asOfficer(() -> processors.register(new RegisterProcessorCommand("communication.whatsapp_cloud",
                new ProcessorDetails("Mensageria Fictícia Ltda.", "SUB_OPERATOR", "Entrega de mensagens de WhatsApp",
                        "Nome, telefone e conteúdo das mensagens", "US", "STANDARD_CONTRACTUAL_CLAUSES", "DPA-01",
                        LocalDate.of(2026, 1, 1), null, true))));
        assertThatThrownBy(() -> asOfficer(() -> processors.register(new RegisterProcessorCommand(
                "communication.whatsapp_cloud", new ProcessorDetails("Outro", "OPERATOR", "x", "y", "BR", null, null,
                        LocalDate.of(2026, 1, 1), null, true))))).isInstanceOf(ConflictException.class);
        ResponseDraftView registered = asOfficer(() -> drafts.draft(request.id()));
        assertThat(registered.alerts()).noneMatch(a -> "communication.whatsapp_cloud".equals(a.recipientKey()));
        assertThat(registered.text()).contains("Mensageria Fictícia Ltda.: Entrega de mensagens de WhatsApp (dados "
                + "tratados em Estados Unidos");
        assertThat(recipient(registered, "communication.whatsapp_cloud").processor().internationalTransfer()).isTrue();

        // operador cadastrado à mão (hospedagem): nenhuma fonte o registra, mas trata os dados de todos os titulares —
        // entra na tabela e no texto com a finalidade do cadastro; o inativo e o vencido ficam de fora
        asOfficer(() -> processors.register(new RegisterProcessorCommand("custom.hospedagem",
                new ProcessorDetails("Nuvem Fictícia Ltda.", "OPERATOR", "Hospedagem do sistema e dos backups",
                        "Todos os dados do sistema", "BR", null, "Contrato HOST-01", LocalDate.of(2026, 1, 1), null,
                        true))));
        asOfficer(() -> processors.register(new RegisterProcessorCommand("custom.antigo",
                new ProcessorDetails("Fornecedor Inativo Ltda.", "OPERATOR", "Suporte antigo", "Contatos", "BR", null,
                        null, LocalDate.of(2026, 1, 1), null, false))));
        asOfficer(() -> processors.register(new RegisterProcessorCommand("custom.vencido",
                new ProcessorDetails("Contrato Vencido Ltda.", "OPERATOR", "Digitalização", "Documentos", "BR", null,
                        null, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), true))));
        ResponseDraftView withManual = asOfficer(() -> drafts.draft(request.id()));
        RecipientView hosting = recipient(withManual, "custom.hospedagem");
        assertThat(hosting.type()).isEqualTo("PROVIDER");
        assertThat(hosting.purposes()).containsExactly("Hospedagem do sistema e dos backups");
        assertThat(hosting.processor()).isNotNull();
        assertThat(withManual.recipients()).extracting(RecipientView::key)
                .doesNotContain("custom.antigo", "custom.vencido");
        assertThat(withManual.alerts()).noneMatch(a -> "custom.hospedagem".equals(a.recipientKey()));
        assertThat(withManual.text()).contains("Nuvem Fictícia Ltda.: Hospedagem do sistema e dos backups",
                "Não compartilhamos seus dados com outras empresas além das listadas acima.")
                .doesNotContain("Fornecedor Inativo", "Contrato Vencido");

        // prestadores configurados sem cadastro: Twilio e SMTP, não o WhatsApp
        List<RecipientInUseView> missing = asOfficer(() -> processors.unregisteredRecipients());
        assertThat(missing).extracting(RecipientInUseView::providerKey)
                .contains("telephony.twilio", "communication.smtp").doesNotContain("communication.whatsapp_cloud");

        // log de acesso e auditoria
        assertThat(count("select count(*) from shared.access_log where event_type = 'SENSITIVE_DATA_VIEWED' "
                + "and details ->> 'view' = 'data_subject_response_draft' and details ->> 'requestId' = ? "
                + "and details ->> 'resourceId' = ?", request.id().toString(), debtor.toString())).isEqualTo(3);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? "
                + "and action = 'data_subject_request.response_draft_generated'", request.id())).isEqualTo(3);
        assertThat(count("select count(*) from shared.audit_event where action = 'data_subject_request.response_draft_generated' "
                + "and entity_id = ? and cast(after_state as text) like '%" + PHONE + "%'", request.id()))
                .isZero();

        // concluir com o texto do rascunho, sem editar: auditoria registra o uso
        asOfficer(() -> requests.start(request.id(), null));
        asOfficer(() -> requests.complete(request.id(), withManual.text(), withManual.textSha256()));
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action = "
                + "'data_subject_request.completed' and metadata ->> 'fromDraft' = 'true' "
                + "and metadata ->> 'draftEdited' = 'false'", request.id())).isEqualTo(1);
        assertThatThrownBy(() -> asOfficer(() -> drafts.draft(request.id())))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "dsr.draft_closed");
    }

    @Test
    void draftRulesConfirmationWithoutSubjectAndIsolationBetweenTenants() {
        // tipo sem rascunho e identidade não confirmada
        RequestView access = asOfficer(() -> requests.open(new OpenRequestCommand("ACCESS", "EMAIL", "Maria Titular",
                CPF, null, null, null)));
        assertThatThrownBy(() -> asOfficer(() -> drafts.draft(access.id())))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "dsr.draft_unavailable");
        RequestView unverified = asOfficer(() -> requests.open(new OpenRequestCommand("CONFIRMATION", "EMAIL",
                "Maria Titular", CPF, null, null, null)));
        assertThatThrownBy(() -> asOfficer(() -> drafts.draft(unverified.id())))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "dsr.identity_not_verified");

        // confirmação com titular na base: categorias e credores
        RequestView confirmation = asOfficer(() -> requests.verifyIdentity(unverified.id(), null));
        ResponseDraftView found = asOfficer(() -> drafts.draft(confirmation.id()));
        assertThat(found.processing()).isTrue();
        assertThat(found.dataCategories()).contains("identificação (nome e CPF/CNPJ)", "mensagens trocadas conosco",
                "registro das ligações telefônicas");
        assertThat(found.text()).contains("confirmamos que tratamos dados pessoais seus", "Banco Fictício");

        // titular fora da base: nada a confirmar
        RequestView stranger = verified("CONFIRMATION", null);
        ResponseDraftView none = asOfficer(() -> drafts.draft(stranger.id()));
        assertThat(none.processing()).isFalse();
        assertThat(none.recipients()).isEmpty();
        assertThat(none.text()).contains("não localizamos dados pessoais seus");

        // outra assessoria não enxerga os fatos desta (RLS): mesmo id de devedor não traz nada
        String otherSlug = "dsrx-" + UUID.randomUUID().toString().substring(0, 8);
        UUID otherTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(otherSlug, "Outra Ltda", "Outra", "11.444.777/0001-61")).id());
        long leaked = tenantExecution.callInTenant(otherTenant, "test", () -> jdbc.queryForObject(
                "select count(*) from communication.conversation where debtor_id = ?", Long.class, debtor));
        assertThat(leaked).isZero();
    }

    @Test
    void apiRequiresPermissionAndAllCreditorsWithoutLeaking() throws Exception {
        RequestView request = verified("INFORMATION_SHARING", CPF);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        String url = "/api/v1/compliance/data-subject-requests/" + request.id() + "/response-draft";

        String body = mvc.perform(get(url).with(authentication(ActorAuthentication.of(officer(CreditorScope.ALL)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("telephony.twilio").doesNotContain(PHONE, EMAIL, "52998224725", "529.982.247");

        // sem a permissão
        Actor operator = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), DefaultRoles.OPERADOR,
                permissionsOf(DefaultRoles.OPERADOR), CreditorScope.ALL);
        String denied = mvc.perform(get(url).with(authentication(ActorAuthentication.of(operator))))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(denied).doesNotContain("Banco", "Twilio");
        // com a permissão, mas acesso a parte dos credores
        String scoped = mvc.perform(get(url).with(authentication(ActorAuthentication.of(
                        officer(CreditorScope.of(Set.of(creditorA)))))))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(scoped).doesNotContain("Banco", "Twilio", "Advocacia");
        assertThatThrownBy(() -> tenantExecution.callAs(officer(CreditorScope.of(Set.of(creditorA))),
                () -> processors.register(new RegisterProcessorCommand("telephony.twilio", new ProcessorDetails(
                        "Twilio", "SUB_OPERATOR", "Ligações", "Telefone", "US", "STANDARD_CONTRACTUAL_CLAUSES", null,
                        LocalDate.of(2026, 1, 1), null, true))))).isInstanceOf(ScopeAccessDeniedException.class);
        // provedores em uso sem cadastro listam contas de todos os credores: exige todos os credores (sem vazar contas)
        assertThatThrownBy(() -> tenantExecution.callAs(officer(CreditorScope.of(Set.of(creditorA))),
                () -> processors.unregisteredRecipients())).isInstanceOf(ScopeAccessDeniedException.class);
        String unregistered = mvc.perform(get("/api/v1/compliance/data-processors/unregistered").with(authentication(
                        ActorAuthentication.of(officer(CreditorScope.of(Set.of(creditorA)))))))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(unregistered).doesNotContain("PSP Fictício", "WhatsApp", "Twilio");
        // só a consulta autorizada foi para o log de acesso
        assertThat(count("select count(*) from shared.access_log where event_type = 'SENSITIVE_DATA_VIEWED' "
                + "and details ->> 'requestId' = ?", request.id().toString())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- apoio

    private static RecipientView recipient(ResponseDraftView draft, String key) {
        return draft.recipients().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    private RequestView verified(String type, String document) {
        return asOfficer(() -> {
            RequestView opened = requests.open(new OpenRequestCommand(type, "EMAIL", "Maria Titular", document,
                    null, "Com quem meus dados foram compartilhados?", null));
            return requests.verifyIdentity(opened.id(), null);
        });
    }

    private static Set<Permission> permissionsOf(String role) {
        return DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst().orElseThrow()
                .permissions();
    }

    private Actor officer(CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), DefaultRoles.COMPLIANCE,
                permissionsOf(DefaultRoles.COMPLIANCE), scope);
    }

    private <T> T asOfficer(Supplier<T> action) {
        return tenantExecution.callAs(officer(CreditorScope.ALL), action);
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private long count(String sql, Object... args) {
        return system(() -> jdbc.queryForObject(sql, Long.class, args));
    }

    private UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
    }

    private void title(UUID creditorId, UUID portfolioId, UUID debtorId, String externalId) {
        system(() -> ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, debtorId, externalId, null, 1,
                LocalDate.of(2026, 3, 10), new BigDecimal("100.00"), null, null), Ids.newId()).id());
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private UUID channelAccount(String channel, String provider, String name, UUID creditorId) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        system(() -> jdbc.update("""
                insert into communication.channel_account (id, tenant_id, version, created_at, updated_at, channel,
                    provider, creditor_id, name, sender_address, routing_key, active)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, 'remetente@exemplo.com', ?, true)""",
                id, tenant, ts(now), ts(now), channel, provider, creditorId, name, "rk-" + id));
        return id;
    }

    private UUID conversation(UUID debtorId, UUID creditorId, String channel, UUID account, Instant at) {
        UUID id = Ids.newId();
        system(() -> jdbc.update("""
                insert into communication.conversation (id, tenant_id, version, created_at, updated_at, debtor_id,
                    creditor_id, channel, channel_account_id, contact_hash, contact_masked, contact_encrypted, status,
                    owner_type, owner_since, last_message_at, unread_count, message_count)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, '(11) *****-4321', 'cifrado', 'OPEN', 'QUEUE', ?, ?, 0, 1)""",
                id, tenant, ts(at), ts(at), debtorId, creditorId, channel, account, "h" + id, ts(at), ts(at)));
        return id;
    }

    private void message(UUID conversationId, String channel, UUID account, Instant at) {
        message(conversationId, channel, account, at, true);
    }

    private void message(UUID conversationId, String channel, UUID account, Instant at, boolean sent) {
        system(() -> jdbc.update("""
                insert into communication.message (id, tenant_id, version, created_at, updated_at, conversation_id,
                    channel, channel_account_id, direction, sender_type, status, status_at, send_attempts, queued_at,
                    sent_at)
                values (?, ?, 0, ?, ?, ?, ?, ?, 'OUTBOUND', 'USER', ?, ?, ?, ?, ?)""",
                Ids.newId(), tenant, ts(at), ts(at), conversationId, channel, account, sent ? "SENT" : "QUEUED", ts(at),
                sent ? 1 : 0, ts(at), sent ? ts(at) : null));
    }

    private UUID pixAccount(String name) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        system(() -> jdbc.update("""
                insert into payment.pix_account (id, tenant_id, version, created_at, updated_at, name, provider, pix_key,
                    api_base_url, token_url, webhook_auth, routing_key, active)
                values (?, ?, 0, ?, ?, ?, 'SANDBOX', 'chave@exemplo.com', 'https://psp.exemplo.com',
                    'https://psp.exemplo.com/token', 'HMAC', ?, true)""",
                id, tenant, ts(now), ts(now), name, "pk-" + id));
        return id;
    }

    private void instruction(UUID account, UUID debtorId, UUID creditorId, String status, Instant at, boolean registered,
                             String failureCode) {
        UUID id = Ids.newId();
        system(() -> jdbc.update("""
                insert into payment.payment_instruction (id, tenant_id, version, created_at, updated_at, type, status,
                    creditor_id, debtor_id, title_id, amount, expires_at, pix_account_id, txid, registered_at,
                    failure_code, cancelled_at)
                values (?, ?, 0, ?, ?, 'PIX_IMMEDIATE', ?, ?, ?, ?, 50, ?, ?, ?, ?, ?, ?)""",
                id, tenant, ts(at), ts(at), status, creditorId, debtorId, Ids.newId(), ts(at.plus(1, ChronoUnit.DAYS)),
                account, "tx" + id.toString().replace("-", ""), registered ? ts(at) : null, failureCode,
                "CANCELLED".equals(status) ? ts(at) : null));
    }

    private UUID providerAccount(String provider, String name) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        system(() -> jdbc.update("""
                insert into telephony.provider_account (id, tenant_id, version, created_at, updated_at, provider, name,
                    caller_id, routing_key, recording_enabled, recording_notice, active)
                values (?, ?, 0, ?, ?, ?, ?, '1130000000', ?, false, false, true)""",
                id, tenant, ts(now), ts(now), provider, name, "tk-" + id));
        return id;
    }

    private void call(UUID account, String provider, UUID debtorId, UUID creditorId, UUID caseId, Instant at,
                      boolean dialed) {
        system(() -> jdbc.update("""
                insert into telephony.phone_call (id, tenant_id, version, created_at, updated_at, direction, origin,
                    status, result, provider_account_id, provider, creditor_id, debtor_id, case_id, attempt_number,
                    number_masked, queued_at, dial_started_at, ended_at, abandoned, recording_requested,
                    recording_announced, interaction_state)
                values (?, ?, 0, ?, ?, 'OUTBOUND', 'CLICK_TO_CALL', ?, ?, ?, ?, ?, ?, ?, 1, '(11) *****-4321', ?, ?, ?,
                    false, false, false, 'NONE')""",
                Ids.newId(), tenant, ts(at), ts(at), dialed ? "COMPLETED" : "CANCELLED",
                dialed ? "ANSWERED" : "CANCELLED", account, provider, creditorId, debtorId, caseId, ts(at),
                dialed ? ts(at) : null, ts(at)));
    }

    private UUID collectionCase(UUID debtorId, UUID creditorId, UUID portfolioId) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        system(() -> jdbc.update("""
                insert into collection.collection_case (id, tenant_id, version, created_at, updated_at, debtor_id,
                    creditor_id, portfolio_id, status, opened_at, open_balance, eligible_balance, open_titles,
                    suspended_titles, disputed_titles, oldest_due_date, balance_updated_at, eligible, owner_type,
                    interaction_count, effective_contact_count)
                values (?, ?, 0, ?, ?, ?, ?, ?, 'OPEN', ?, 100, 100, 1, 0, 0, date '2026-03-10', ?, true, 'NONE', 0, 0)""",
                id, tenant, ts(now), ts(now), debtorId, creditorId, portfolioId, ts(now), ts(now)));
        return id;
    }

    private UUID lawFirm(String name) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        system(() -> jdbc.update("""
                insert into legal.law_firm (id, tenant_id, version, created_at, updated_at, name, cnpj, active)
                values (?, ?, 0, ?, ?, ?, '11222333000181', true)""", id, tenant, ts(now), ts(now), name));
        return id;
    }

    private void legalMatter(UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, UUID firm, Instant at) {
        system(() -> jdbc.update("""
                insert into legal.legal_matter (id, tenant_id, version, created_at, updated_at, collection_case_id,
                    debtor_id, creditor_id, portfolio_id, origin, stage, stage_changed_at, responsible_type, law_firm_id,
                    debt_amount, expenses_total, recovered_amount, collection_hold, referred_at)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, 'MANUAL', 'UNDER_REVIEW', ?, 'LAW_FIRM', ?, 100, 0, 0, false, ?)""",
                Ids.newId(), tenant, ts(at), ts(at), caseId, debtorId, creditorId, portfolioId, ts(at), firm, ts(at)));
    }
}
