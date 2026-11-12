package br.com.gestorcobranca.debt;

import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.AddRepresentativeCommand;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.RepresentativeView;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.UpdateRepresentativeCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.Outcome;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.RepresentativeRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.UpsertResult;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.UpdateContactCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
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
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.privacy.RelatedPersonSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cobrança B2B no debt contra PostgreSQL real (docs/tecnico/desenho/cobranca-b2b.md §10): carga com nome fantasia,
 * pessoa de contato e representantes (idempotente), matriz e filiais pela raiz do CNPJ com escopo por credor,
 * representantes (regras, validade, CPF cifrado, solicitação de titular) e anonimização da empresa. Dados fictícios.
 */
class CompanyDebtorIT extends IntegrationTest {

    private static final String HEAD_OFFICE = "60.012.345/0001-97";
    private static final String BRANCH_2 = "60.012.345/0002-78";
    private static final String BRANCH_3 = "60.012.345/0003-59";
    private static final String OTHER_COMPANY = "70.098.765/0001-91";
    private static final String PARTNER_CPF = "123.456.789-09";
    private static final String ATTORNEY_CPF = "987.654.321-00";
    private static final String OTHER_CPF = "246.813.579-28";

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
    DebtorUseCases debtors;
    @Autowired
    DebtorContactUseCases contacts;
    @Autowired
    CompanyRepresentativeUseCases representatives;
    @Autowired
    List<RelatedPersonSource> relatedPersonSources;
    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;

    @BeforeEach
    void setUp() {
        String slug = "b2b-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53", null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
    }

    private UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T asUserOf(UUID creditorId, EnumSet<Permission> permissions, Supplier<T> action) {
        Actor user = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "operador", permissions,
                CreditorScope.of(List.of(creditorId)));
        return tenantExecution.callAs(user, action);
    }

    private <T> T asUserOf(UUID creditorId, Supplier<T> action) {
        return asUserOf(creditorId, EnumSet.of(Permission.DEBTOR_VIEW, Permission.DEBTOR_MANAGE), action);
    }

    private UUID companyWithTitle(String cnpj, String name, UUID creditorId, UUID portfolioId, String externalId) {
        UUID id = system(() -> ingestion.upsertDebtor(new DebtorRecord(cnpj, name, null, List.of())).id());
        system(() -> ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, id, externalId,
                new ContractRecord("CTR-" + externalId, "Capital de giro", LocalDate.of(2025, 6, 1),
                        new BigDecimal("30000.00")), 1, LocalDate.of(2026, 3, 10), new BigDecimal("1000.00"), null,
                null), Ids.newId()));
        return id;
    }

    private DebtorRecord companyRecord() {
        return new DebtorRecord(HEAD_OFFICE, "Comercial Fictícia de Pães Ltda", LocalDate.of(1990, 1, 1),
                List.of(new ContactRecord("PHONE", "(11) 98765-4321", "WORK", true, "Ana Fictícia", "FINANCE"),
                        new ContactRecord("EMAIL", "financeiro@padaria-ficticia.com.br", null, false, null, "ACCOUNTS_PAYABLE")),
                "Padaria Fictícia",
                List.of(new RepresentativeRecord(PARTNER_CPF, "João Sócio Fictício", "MANAGING_PARTNER", true),
                        new RepresentativeRecord(ATTORNEY_CPF, "Maria Procuradora Fictícia", "ATTORNEY_IN_FACT", false)));
    }

    @Test
    void importsCompanyWithTradeNameContactPersonsAndRepresentativesIdempotently() {
        UpsertResult first = system(() -> ingestion.upsertDebtor(companyRecord()));
        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(first.warnings()).isEmpty();

        DebtorView company = system(() -> debtors.get(first.id()));
        assertThat(company.personType()).isEqualTo("COMPANY");
        assertThat(company.tradeName()).isEqualTo("Padaria Fictícia");
        assertThat(company.establishment()).isEqualTo("Matriz");
        assertThat(company.birthDate()).as("empresa não tem data de nascimento").isNull();

        List<ContactView> list = system(() -> contacts.list(first.id()));
        ContactView phone = list.stream().filter(c -> c.type().equals("PHONE")).findFirst().orElseThrow();
        assertThat(phone.maskedPersonName()).isEqualTo("A***");
        assertThat(phone.personRole()).isEqualTo("FINANCE");
        assertThat(system(() -> contacts.revealContact(first.id(), phone.id())).personName()).isEqualTo("Ana Fictícia");
        assertThat(list.stream().filter(c -> c.type().equals("EMAIL")).findFirst().orElseThrow().personRole())
                .isEqualTo("ACCOUNTS_PAYABLE");

        List<RepresentativeView> reps = system(() -> representatives.list(first.id()));
        assertThat(reps).hasSize(2);
        RepresentativeView partner = reps.stream().filter(r -> r.role().equals("MANAGING_PARTNER")).findFirst().orElseThrow();
        assertThat(partner.maskedDocument()).isEqualTo("***.456.789-**");
        assertThat(partner.negotiatesToday()).isTrue();
        assertThat(partner.source()).isEqualTo("IMPORT");
        assertThat(reps.stream().filter(r -> r.role().equals("ATTORNEY_IN_FACT")).findFirst().orElseThrow()
                .negotiatesToday()).isFalse();

        // CPF, nome e nome fantasia só cifrados no banco
        Map<String, Object> row = system(() -> jdbcTemplate.queryForMap(
                "select document_encrypted, name_encrypted from debt.company_representative where id = ?", partner.id()));
        assertThat(row.get("document_encrypted").toString()).doesNotContain("12345678909");
        assertThat(row.get("name_encrypted").toString()).doesNotContain("João");
        assertThat(system(() -> jdbcTemplate.queryForObject(
                "select trade_name_encrypted from debt.debtor where id = ?", String.class, first.id()))).doesNotContain("Padaria");

        // reimportar o mesmo registro não duplica nem altera
        UpsertResult again = system(() -> ingestion.upsertDebtor(companyRecord()));
        assertThat(again.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(system(() -> representatives.list(first.id()))).hasSize(2);
        assertThat(system(() -> contacts.list(first.id()))).hasSize(2);
    }

    @Test
    void representativeOnIndividualDebtorAndInvalidRolesBecomeWarnings() {
        UpsertResult result = system(() -> ingestion.upsertDebtor(new DebtorRecord(OTHER_CPF, "Pessoa Fictícia", null,
                List.of(new ContactRecord("PHONE", "(11) 98765-1111", null, false, "Irmã", "SISTER")), null,
                List.of(new RepresentativeRecord(PARTNER_CPF, "João", "MANAGING_PARTNER", true)))));
        assertThat(result.warnings()).contains("contact.person_role_invalid", "representative.company_required");
        assertThat(system(() -> contacts.list(result.id())).getFirst().maskedPersonName()).isEqualTo("I***");
    }

    @Test
    void otherUnitsOfTheCompanyFollowTheCnpjRootAndTheCreditorScope() {
        UUID headOffice = companyWithTitle(HEAD_OFFICE, "Comercial Fictícia Ltda", creditorA, portfolioA, "T-1");
        UUID branch2 = companyWithTitle(BRANCH_2, "Comercial Fictícia Ltda", creditorA, portfolioA, "T-2");
        UUID branch3 = companyWithTitle(BRANCH_3, "Comercial Fictícia Ltda", creditorB, portfolioB, "T-3");
        companyWithTitle(OTHER_COMPANY, "Outra Empresa Fictícia Ltda", creditorA, portfolioA, "T-4");

        assertThat(system(() -> debtors.get(branch2)).establishment()).isEqualTo("Filial 0002");
        assertThat(system(() -> debtors.otherUnits(headOffice, PageQuery.of(0, 50))).content())
                .extracting(DebtorView::id).containsExactlyInAnyOrder(branch2, branch3);
        // escopo por credor: a filial só do credor B não aparece para quem vê só o A
        assertThat(asUserOf(creditorA, () -> debtors.otherUnits(headOffice, PageQuery.of(0, 50))).content())
                .extracting(DebtorView::id).containsExactly(branch2);
        // pessoa física não tem unidades
        UUID individual = system(() -> ingestion.upsertDebtor(new DebtorRecord(OTHER_CPF, "Pessoa", null, List.of())).id());
        assertThat(system(() -> debtors.otherUnits(individual, PageQuery.of(0, 50))).content()).isEmpty();
    }

    @Test
    void documentRootOfOlderCompaniesIsFilledOnTheNextImport() {
        UUID id = system(() -> ingestion.upsertDebtor(new DebtorRecord(HEAD_OFFICE, "Comercial", null, List.of())).id());
        UUID branch = system(() -> ingestion.upsertDebtor(new DebtorRecord(BRANCH_2, "Comercial", null, List.of())).id());
        system(() -> jdbcTemplate.update("update debt.debtor set document_root_hash = null where id = ?", id));
        assertThat(system(() -> debtors.otherUnits(branch, PageQuery.of(0, 50))).content()).isEmpty();

        assertThat(system(() -> ingestion.upsertDebtor(new DebtorRecord(HEAD_OFFICE, "Comercial", null, List.of())))
                .outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(system(() -> debtors.otherUnits(branch, PageQuery.of(0, 50))).content())
                .extracting(DebtorView::id).containsExactly(id);
    }

    @Test
    void representativeRulesValidityAndNegotiatorLookup() {
        UUID company = companyWithTitle(HEAD_OFFICE, "Comercial Fictícia Ltda", creditorA, portfolioA, "T-1");
        UUID individual = companyWithTitle(OTHER_CPF, "Pessoa Fictícia", creditorA, portfolioA, "T-2");

        RepresentativeView added = asUserOf(creditorA, () -> representatives.add(company, new AddRepresentativeCommand(
                PARTNER_CPF, "João Sócio", "MANAGING_PARTNER", true, null, "contrato social de 2024")));
        assertThat(added.negotiatesToday()).isTrue();
        assertThat(added.evidenceNote()).isEqualTo("contrato social de 2024");

        assertThatThrownBy(() -> asUserOf(creditorA, () -> representatives.add(company, new AddRepresentativeCommand(
                "123.456.789-09", "Outro nome", "OTHER", false, null, null))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "representative.duplicate");
        assertThatThrownBy(() -> asUserOf(creditorA, () -> representatives.add(individual, new AddRepresentativeCommand(
                PARTNER_CPF, "João", "OTHER", true, null, null))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "representative.company_required");
        assertThatThrownBy(() -> asUserOf(creditorA, () -> representatives.add(company, new AddRepresentativeCommand(
                OTHER_COMPANY, "Empresa", "OTHER", true, null, null))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "representative.cpf_required");

        assertThat(system(() -> representatives.negotiator(company, "12345678909"))).isPresent();
        assertThat(system(() -> representatives.negotiator(company, ATTORNEY_CPF))).isEmpty();
        assertThat(system(() -> representatives.hasNegotiators(company))).isTrue();

        // validade vencida: deixa de negociar
        asUserOf(creditorA, () -> representatives.update(company, added.id(), new UpdateRepresentativeCommand(
                "João Sócio", "MANAGING_PARTNER", true, LocalDate.of(2020, 1, 1), null)));
        assertThat(system(() -> representatives.negotiator(company, PARTNER_CPF))).isEmpty();
        assertThat(system(() -> representatives.hasNegotiators(company))).isFalse();

        // encerrado: continua listado, sem negociar; o mesmo CPF pode voltar como novo representante
        RepresentativeView ended = asUserOf(creditorA, () -> representatives.end(company, added.id(), "Saiu da sociedade"));
        assertThat(ended.active()).isFalse();
        asUserOf(creditorA, () -> representatives.add(company, new AddRepresentativeCommand(PARTNER_CPF, "João Sócio",
                "MANAGING_PARTNER", true, null, null)));
        assertThat(system(() -> representatives.list(company))).hasSize(2);

        // escopo: quem vê só o credor B não alcança a empresa (404 do devedor)
        assertThatThrownBy(() -> asUserOf(creditorB, () -> representatives.list(company)))
                .isInstanceOf(RuntimeException.class);
        // revelação do CPF exige permissão própria
        assertThatThrownBy(() -> asUserOf(creditorA, () -> representatives.revealDocument(company, added.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asUserOf(creditorA, EnumSet.of(Permission.DEBTOR_VIEW, Permission.DEBTOR_SENSITIVE_VIEW),
                () -> representatives.revealDocument(company, added.id())).document()).isEqualTo("123.456.789-09");
        // alterar exige DEBTOR_MANAGE
        assertThatThrownBy(() -> asUserOf(creditorA, EnumSet.of(Permission.DEBTOR_VIEW), () -> representatives.add(
                company, new AddRepresentativeCommand(ATTORNEY_CPF, "Maria", "OTHER", true, null, null))))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void importWithoutRoleOrPowerKeepsWhatWasRegisteredByHand() {
        UUID company = companyWithTitle(HEAD_OFFICE, "Comercial Fictícia Ltda", creditorA, portfolioA, "T-1");
        RepresentativeView manual = asUserOf(creditorA, () -> representatives.add(company, new AddRepresentativeCommand(
                PARTNER_CPF, "João Sócio Fictício", "MANAGING_PARTNER", true, null, "contrato social de 2024")));

        // carga só com CPF e nome (papel e "negocia" não informados): não apaga papel nem poder
        system(() -> ingestion.upsertDebtor(new DebtorRecord(HEAD_OFFICE, "Comercial Fictícia Ltda", null, List.of(), null,
                List.of(new RepresentativeRecord(PARTNER_CPF, "João Sócio Fictício", null, null),
                        new RepresentativeRecord(ATTORNEY_CPF, "Maria Procuradora Fictícia", null, null)))));
        List<RepresentativeView> reps = system(() -> representatives.list(company));
        assertThat(reps).hasSize(2);
        RepresentativeView kept = reps.stream().filter(r -> r.id().equals(manual.id())).findFirst().orElseThrow();
        assertThat(kept.role()).isEqualTo("MANAGING_PARTNER");
        assertThat(kept.canNegotiate()).isTrue();
        assertThat(kept.negotiatesToday()).isTrue();
        assertThat(kept.evidenceNote()).isEqualTo("contrato social de 2024");
        assertThat(system(() -> representatives.negotiator(company, PARTNER_CPF))).isPresent();
        // representante novo sem papel nem poder informados: OTHER e sem poder
        RepresentativeView created = reps.stream().filter(r -> !r.id().equals(manual.id())).findFirst().orElseThrow();
        assertThat(created.role()).isEqualTo("OTHER");
        assertThat(created.canNegotiate()).isFalse();

        // informado explicitamente, a carga continua atualizando
        system(() -> ingestion.upsertDebtor(new DebtorRecord(HEAD_OFFICE, "Comercial Fictícia Ltda", null, List.of(), null,
                List.of(new RepresentativeRecord(PARTNER_CPF, "João Sócio Fictício", "ATTORNEY_IN_FACT", false)))));
        RepresentativeView updated = system(() -> representatives.get(company, manual.id()));
        assertThat(updated.role()).isEqualTo("ATTORNEY_IN_FACT");
        assertThat(updated.canNegotiate()).isFalse();
    }

    @Test
    void representativeIsLocatedByCpfForDataSubjectRequestsAndRemovedWithTheCompany() {
        UUID company = system(() -> ingestion.upsertDebtor(companyRecord()).id());
        RelatedPersonSource source = relatedPersonSources.stream().filter(s -> s.id().equals("debt")).findFirst()
                .orElseThrow();
        List<RelatedPersonSource.RelatedPersonLink> links = system(() -> source.linksOf(PARTNER_CPF, null));
        assertThat(links).singleElement().satisfies(l -> {
            assertThat(l.debtorId()).isEqualTo(company);
            assertThat(l.role()).isEqualTo("MANAGING_PARTNER");
            assertThat(l.link().route()).isEqualTo("/devedores/" + company);
        });
        assertThat(system(() -> source.linksOf(OTHER_CPF, null))).isEmpty();
        // escopo vazio de credores (empresa sem títulos no escopo): nada
        assertThat(system(() -> source.linksOf(PARTNER_CPF, java.util.Set.of(creditorA)))).isEmpty();

        system(() -> debtors.anonymize(company, "Solicitação do titular"));
        assertThat(system(() -> jdbcTemplate.queryForObject(
                "select count(*) from debt.company_representative where company_debtor_id = ?", Long.class, company)))
                .isZero();
        DebtorView anonymized = system(() -> debtors.get(company));
        assertThat(anonymized.tradeName()).isNull();
        assertThat(anonymized.establishment()).isNull();
    }

    @Test
    void contactPersonCanBeEditedWithoutKnowingTheFullName() {
        UUID company = system(() -> ingestion.upsertDebtor(companyRecord()).id());
        ContactView phone = system(() -> contacts.list(company)).stream().filter(c -> c.type().equals("PHONE"))
                .findFirst().orElseThrow();
        // nome nulo mantém o atual: a tela só conhece o nome mascarado
        ContactView updated = system(() -> contacts.update(company, phone.id(), new UpdateContactCommand("WORK", true, 0,
                null, "OWNER_PARTNER", true)));
        assertThat(updated.maskedPersonName()).isEqualTo("A***");
        assertThat(updated.personRole()).isEqualTo("OWNER_PARTNER");
        assertThat(updated.decisionMaker()).isTrue();
        assertThatThrownBy(() -> system(() -> contacts.update(company, phone.id(), new UpdateContactCommand("WORK", true,
                0, "", null, true))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "contact.decision_maker_without_person");
    }
}
