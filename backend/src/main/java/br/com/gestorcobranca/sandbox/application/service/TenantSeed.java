package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Person;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData;
import br.com.gestorcobranca.shared.security.Actor;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Identificadores de uma assessoria do sandbox, preenchidos à medida que as etapas da carga avançam. Uso exclusivo da
 * thread da carga.
 */
final class TenantSeed {

    final TenantSpec spec;
    final DemoPortfolioData data;
    final LocalDate today;
    UUID tenantId;
    /** Ator (usuário de demonstração) por chave do catálogo ({@link Person#key()}). */
    final Map<String, Actor> actors = new LinkedHashMap<>();
    final Map<String, UUID> users = new LinkedHashMap<>();
    /** Por prefixo do credor. */
    final Map<String, UUID> creditors = new LinkedHashMap<>();
    final Map<String, UUID> contracts = new LinkedHashMap<>();
    final Map<String, LocalDate> contractStarts = new LinkedHashMap<>();
    /** Por código da carteira. */
    final Map<String, UUID> portfolios = new LinkedHashMap<>();
    UUID teamId;
    final Map<String, UUID> queues = new LinkedHashMap<>();
    UUID whatsappAccountId;
    UUID lawFirmId;

    TenantSeed(TenantSpec spec, LocalDate today) {
        this.spec = spec;
        this.today = today;
        this.data = DemoPortfolioData.generate(spec, today);
    }

    Actor actor(String personKey) {
        Actor actor = actors.get(personKey);
        if (actor == null) {
            throw new IllegalStateException("usuário de demonstração '" + personKey + "' ainda não preparado");
        }
        return actor;
    }

    UUID user(String personKey) {
        return actor(personKey).id();
    }

    CreditorSpec creditor(int index) {
        return spec.creditors().get(index);
    }

    UUID creditorId(int index) {
        return creditors.get(creditor(index).prefix());
    }

    String portfolioCode(int creditorIndex, int portfolioIndex) {
        CreditorSpec c = creditor(creditorIndex);
        return c.portfolios().get(portfolioIndex).code(c);
    }

    UUID portfolioId(int creditorIndex, int portfolioIndex) {
        return portfolios.get(portfolioCode(creditorIndex, portfolioIndex));
    }

    /** Dívida fictícia do devedor de índice {@code index} na carteira. */
    DemoPortfolioData.Debt debt(int creditorIndex, int portfolioIndex, int index) {
        return data.debts(portfolioCode(creditorIndex, portfolioIndex)).get(index);
    }
}
