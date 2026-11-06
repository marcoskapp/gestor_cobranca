package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contatos do devedor na API pública (achado API-07, decisão do responsável): os contatos são da assessoria e
 * compartilhados entre os credores dela — a integração de um credor lista e invalida contato do devedor que veio da
 * carga de outro credor da mesma assessoria —, mas nunca atravessam assessorias. Um token da assessoria A recebe 404
 * para o devedor e o contato da assessoria B, inclusive combinando um devedor próprio com o id do contato de B, e o
 * contato de B continua válido (RLS em {@code debt.debtor_contact}, {@code own(id)} no controller e
 * {@code loadVisible} em {@code DebtorContactService}).
 */
class PublicApiContactTenantIsolationIT extends PublicApiTestSupport {

    private static final String INVALIDATE_BODY = "{\"reason\":\"telefone de terceiro\"}";

    @Autowired
    DebtIngestionPort ingestion;

    @Test
    void contactsAreSharedAmongCreditorsOfTheSameTenantButNeverAcrossTenants() throws Exception {
        // assessoria A: devedor com títulos nos dois credores; o contato chegou pela carga do credor B
        UUID debtorA = system(() -> ingestion.upsertDebtor(new DebtorRecord("52998224725", "Devedora Fictícia A",
                LocalDate.of(1980, 1, 1), List.of(new ContactRecord("PHONE", "+5511987654321", "MOBILE", false)))).id());
        system(() -> {
            UUID batch = Ids.newId();
            ingestion.upsertTitle(title(creditorA, portfolioA, debtorA, "T-A-1"), batch);
            ingestion.upsertTitle(title(creditorB, portfolioB, debtorA, "T-B-1"), batch);
            return null;
        });
        UUID contactA = contactOf(tenant, debtorA);

        // assessoria B: outro devedor fictício com contato próprio
        Other other = otherTenantWithDebtorContact();

        IssuedToken tokenA = issue(creditorA, Set.of("debtors:read", "debtors:write"), null, List.of());
        String base = "/public-api/v1/debtors/";

        // mesma assessoria: o credor A vê e invalida o contato vindo da carga do credor B (exceção documentada)
        HttpResponse<String> ownList = get(base + debtorA + "/contacts", tokenA.token());
        assertThat(ownList.statusCode()).isEqualTo(200);
        assertThat(ownList.body()).contains(contactA.toString()).doesNotContain(other.contact().toString());

        // outra assessoria: devedor e contato de B inexistentes para o token de A
        assertThat(get(base + other.debtor() + "/contacts", tokenA.token()).statusCode()).isEqualTo(404);
        HttpResponse<String> crossInvalidate = call("POST", base + other.debtor() + "/contacts/" + other.contact()
                + "/invalidate", tokenA.token(), INVALIDATE_BODY, Map.of());
        assertThat(crossInvalidate.statusCode()).isEqualTo(404);
        assertThat(body(crossInvalidate)).containsEntry("code", "debtor.not_found");
        // devedor próprio com o id do contato de B: o contato não é encontrado (RLS + loadVisible)
        HttpResponse<String> mixedInvalidate = call("POST", base + debtorA + "/contacts/" + other.contact()
                + "/invalidate", tokenA.token(), INVALIDATE_BODY, Map.of());
        assertThat(mixedInvalidate.statusCode()).isEqualTo(404);
        assertThat(body(mixedInvalidate)).containsEntry("code", "debtor_contact.not_found");

        // o contato da assessoria B continua como estava, sem auditoria de invalidação
        Map<String, Object> contactB = inTenant(other.tenant(), () -> jdbc.queryForMap(
                "select status, invalid_reason from debt.debtor_contact where id = ?", other.contact()));
        assertThat(contactB.get("status")).isNotEqualTo("INVALID");
        assertThat(contactB.get("invalid_reason")).isNull();
        long audits = inTenant(other.tenant(), () -> jdbc.queryForObject("""
                select count(*) from shared.audit_event where action = 'debtor_contact.invalidated' and entity_id = ?""",
                Long.class, other.contact()));
        assertThat(audits).isZero();

        // e o contato compartilhado da assessoria A pode ser invalidado pela integração do credor A
        HttpResponse<String> shared = call("POST", base + debtorA + "/contacts/" + contactA + "/invalidate",
                tokenA.token(), INVALIDATE_BODY, Map.of());
        assertThat(shared.statusCode()).isEqualTo(200);
        assertThat(system(() -> jdbc.queryForObject("select status from debt.debtor_contact where id = ?", String.class,
                contactA))).isEqualTo("INVALID");
    }

    private record Other(UUID tenant, UUID debtor, UUID contact) {
    }

    private Other otherTenantWithDebtorContact() {
        String slug = "pb-" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantB = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Outra Assessoria Ltda", "Outra Assessoria",
                        "11.444.777/0001-61")).id());
        UUID creditor = inTenant(tenantB, () -> creditors.create(new CreditorCommand("Financeira C S.A.", "Financeira C",
                "11.222.333/0001-81", null, null)).id());
        UUID portfolio = inTenant(tenantB, () -> {
            UUID contract = contracts.create(new CreateContractCommand(creditor, "CT-C-01",
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            UUID id = portfolios.create(new CreatePortfolioCommand(creditor, contract, "C-01", "Carteira C-01", null,
                    LocalDate.of(2026, 1, 1), null)).id();
            portfolios.changeStatus(id, "ACTIVE", null, null);
            return id;
        });
        UUID debtor = inTenant(tenantB, () -> ingestion.upsertDebtor(new DebtorRecord("11144477735",
                "Devedor Fictício B", LocalDate.of(1975, 5, 5),
                List.of(new ContactRecord("PHONE", "+5511955554444", "MOBILE", false)))).id());
        inTenant(tenantB, () -> ingestion.upsertTitle(title(creditor, portfolio, debtor, "T-C-1"), Ids.newId()));
        return new Other(tenantB, debtor, contactOf(tenantB, debtor));
    }

    private UUID contactOf(UUID tenantId, UUID debtorId) {
        return inTenant(tenantId, () -> jdbc.queryForObject(
                "select id from debt.debtor_contact where debtor_id = ?", UUID.class, debtorId));
    }

    private static TitleRecord title(UUID creditor, UUID portfolio, UUID debtor, String externalId) {
        return new TitleRecord(creditor, portfolio, debtor, externalId, null, 1, LocalDate.of(2026, 3, 10),
                new BigDecimal("250.00"), null, null);
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return tenantExecution.callInTenant(tenantId, "test", action);
    }
}
