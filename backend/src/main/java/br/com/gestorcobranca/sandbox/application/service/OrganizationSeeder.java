package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractView;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.UpdateTenantSettingsCommand;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.PortfolioSpec;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Assessoria (janela de contato), credores, contratos de prestação de serviço e carteiras — cadastrados pelo
 * administrador da assessoria pelos casos de uso do módulo organization. Idempotente: o que já existe é reaproveitado.
 */
@SandboxSeedComponent
class OrganizationSeeder {

    private final TenantAdministration tenants;
    private final CreditorUseCases creditors;
    private final ServiceContractUseCases contracts;
    private final PortfolioUseCases portfolios;
    private final SeedRuntime rt;

    OrganizationSeeder(TenantAdministration tenants, CreditorUseCases creditors, ServiceContractUseCases contracts,
                       PortfolioUseCases portfolios, SeedRuntime rt) {
        this.tenants = tenants;
        this.creditors = creditors;
        this.contracts = contracts;
        this.portfolios = portfolios;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        Actor admin = seed.actor("admin");
        // horário de contato da assessoria: dias úteis e sábado, 8h às 20h (a política de contato refina por canal)
        rt.as(admin, () -> tenants.updateCurrentTenant(new UpdateTenantSettingsCommand(seed.spec.legalName(),
                seed.spec.tradeName(), LocalTime.of(8, 0), LocalTime.of(20, 0), true, false)));
        for (int c = 0; c < seed.spec.creditors().size(); c++) {
            CreditorSpec spec = seed.spec.creditors().get(c);
            String document = new FictitiousData(seed.spec.seed() * 7 + c).cnpj();
            String contact = "recuperacao@" + FictitiousData.slug(spec.tradeName()) + ".exemplo.test";
            UUID creditorId = rt.as(admin, () -> creditors.search(spec.prefix(), null, PageQuery.of(0, 20)).content()
                    .stream().filter(v -> spec.prefix().equals(v.externalCode())).findFirst()
                    .orElseGet(() -> creditors.create(new CreditorCommand(spec.legalName(), spec.tradeName(), document,
                            spec.prefix(), contact)))).id();
            seed.creditors.put(spec.prefix(), creditorId);

            LocalDate start = seed.today.minusYears(1).withDayOfMonth(1);
            String number = "CPS-" + spec.prefix() + "-" + start.getYear();
            ContractView contract = rt.as(admin, () -> contracts.listByCreditor(creditorId).stream()
                    .filter(v -> number.equals(v.number())).findFirst()
                    .orElseGet(() -> contracts.create(new CreateContractCommand(creditorId, number,
                            new ContractTermsCommand(start, null, 180, 10,
                                    "Contrato de prestação de serviços de cobrança extrajudicial e judicial (fictício)")))));
            seed.contracts.put(spec.prefix(), contract.id());
            seed.contractStarts.put(spec.prefix(), contract.startDate());

            for (int p = 0; p < spec.portfolios().size(); p++) {
                PortfolioSpec portfolio = spec.portfolios().get(p);
                String code = portfolio.code(spec);
                LocalDate receivedOn = contract.startDate().plusDays(20L * (p + 1));
                PortfolioView view = rt.as(admin, () -> portfolios.search(creditorId, null, code, PageQuery.of(0, 20))
                        .content().stream().filter(v -> code.equals(v.code())).findFirst()
                        .orElseGet(() -> portfolios.create(new CreatePortfolioCommand(creditorId, contract.id(), code,
                                portfolio.name(), "Carteira fictícia de demonstração — " + portfolio.product(),
                                receivedOn, seed.today.plusYears(1)))));
                if (!"ACTIVE".equals(view.status())) {
                    rt.as(admin, () -> portfolios.changeStatus(view.id(), "ACTIVE", "Início da operação (sandbox)"));
                }
                seed.portfolios.put(code, view.id());
            }
        }
    }
}
