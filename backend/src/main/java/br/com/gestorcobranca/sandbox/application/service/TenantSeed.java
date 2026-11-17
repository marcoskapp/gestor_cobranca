package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Person;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistorySpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData;
import br.com.gestorcobranca.shared.security.Actor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Identificadores de uma assessoria do sandbox, preenchidos à medida que as etapas da carga avançam. Uso exclusivo da
 * thread da carga.
 */
final class TenantSeed {

    final TenantSpec spec;
    final DemoPortfolioData data;
    final LocalDate today;
    /** Histórico fictício da assessoria (nulo = só o presente). */
    final HistorySpec history;
    /** Primeiro dia do histórico: 1º dia do mês, 12 meses antes do mês corrente (início dos contratos). */
    final LocalDate historyStart;
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
    /** Equipes por código ({@code DemoHistory.Team#code}; a "Mesa de negociação" é {@code MESA}). */
    final Map<String, UUID> teams = new LinkedHashMap<>();
    final Map<String, UUID> queues = new LinkedHashMap<>();
    UUID whatsappAccountId;
    UUID lawFirmId;

    TenantSeed(TenantSpec spec, LocalDate today) {
        this.spec = spec;
        this.today = today;
        this.data = DemoPortfolioData.generate(spec, today);
        this.history = DemoHistory.of(spec.slug()).orElse(null);
        this.historyStart = YearMonth.from(today).minusMonths(DemoHistory.MONTHS).atDay(1);
        if (history != null) {
            data.addHistory(history, spec.seed() * 17 + 3, p -> receivedOn(p.receivedMonth()));
        }
    }

    boolean hasHistory() {
        return history != null;
    }

    /** Instante da configuração inicial da assessoria com histórico: 1º dia do histórico, 7h30 (fuso de operação). */
    LocalDateTime historyOrigin() {
        return historyStart.atTime(LocalTime.of(7, 30));
    }

    /** Dia em que a carteira do mês {@code month} do histórico chega: 1º dia útil do mês + 2 dias úteis. */
    LocalDate receivedOn(int month) {
        LocalDate day = historyStart.plusMonths(month);
        int business = 0;
        while (business < 3) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                business++;
            }
            if (business < 3) {
                day = day.plusDays(1);
            }
        }
        return day;
    }

    /** Todas as pessoas de demonstração da assessoria: catálogo principal e, com histórico, supervisores e negociadores. */
    List<Person> people() {
        return history == null ? spec.people() : Stream.concat(spec.people().stream(), history.people().stream()).toList();
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
