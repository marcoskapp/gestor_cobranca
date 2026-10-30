package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases;
import br.com.gestorcobranca.publicapi.application.port.out.CsvWriter;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Relatórios de consumo para as telas (por dia, rota e token) e exportação CSV, no escopo de credores do ator. */
@Service
@Transactional(readOnly = true)
class UsageReportService implements PublicApiUsageUseCases {

    private static final int MAX_DAYS = 366;

    private final UsageStore store;
    private final IntegrationTokenRepository tokens;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final CsvWriter csv;

    UsageReportService(UsageStore store, IntegrationTokenRepository tokens, AccessScope accessScope,
                       CurrentActor currentActor, AccessLogger accessLogger, CsvWriter csv) {
        this.store = store;
        this.tokens = tokens;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.csv = csv;
    }

    @Override
    public UsageReport report(LocalDate from, LocalDate to, UUID creditorId, UUID tokenId) {
        UsageStore.Filter filter = filter(from, to, creditorId, tokenId);
        List<UsageStore.TokenRow> tokenRows = store.byToken(filter);
        Map<UUID, IntegrationToken> names = tokens.findAllByIdIn(tokenRows.stream().map(UsageStore.TokenRow::tokenId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(IntegrationToken::getId, Function.identity()));
        return new UsageReport(from, to, UsageTotalsMapper.view(store.totals(filter)),
                store.daily(filter, TimeConfig.BUSINESS_ZONE_ID).stream()
                        .map(r -> new DailyUsage(r.day(), UsageTotalsMapper.view(r.totals()))).toList(),
                store.byRoute(filter).stream()
                        .map(r -> new RouteUsage(r.method(), r.route(), UsageTotalsMapper.view(r.totals()))).toList(),
                tokenRows.stream().map(r -> {
                    IntegrationToken t = names.get(r.tokenId());
                    return new TokenUsage(r.tokenId(), t == null ? null : t.getDisplayPrefix(),
                            t == null ? null : t.getName(), r.creditorId(), UsageTotalsMapper.view(r.totals()));
                }).toList());
    }

    @Override
    public void exportCsv(LocalDate from, LocalDate to, UUID creditorId, UUID tokenId, OutputStream output) {
        UsageStore.Filter filter = filter(from, to, creditorId, tokenId);
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(), Map.of(
                "resource", "public_api_usage", "from", from.toString(), "to", to.toString(),
                "creditorId", String.valueOf(creditorId), "tokenId", String.valueOf(tokenId))));
        try (CsvWriter.Sheet sheet = csv.open(output, List.of("dia", "credor_id", "token_id", "metodo", "rota",
                "requisicoes", "requisicoes_cobraveis", "limitadas_429", "erros_cliente_4xx", "erros_servidor_5xx",
                "bytes_entrada", "bytes_saida", "registros", "latencia_media_ms"))) {
            store.detailed(filter, TimeConfig.BUSINESS_ZONE_ID, r -> sheet.row(List.of(r.day(), r.creditorId(),
                    r.tokenId(), r.method(), r.route(), r.totals().requests(), r.totals().billableRequests(),
                    r.totals().throttled(), r.totals().clientErrors(), r.totals().serverErrors(), r.totals().bytesIn(),
                    r.totals().bytesOut(), r.totals().records(),
                    r.totals().requests() == 0 ? 0 : r.latencyMsTotal() / r.totals().requests())));
        }
    }

    private UsageStore.Filter filter(LocalDate from, LocalDate to, UUID creditorId, UUID tokenId) {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        Guard.require(from != null && to != null && !to.isBefore(from), "public_api_usage.period_invalid",
                "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(from, to) < MAX_DAYS, "public_api_usage.period_too_long",
                "Período de até 366 dias");
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        if (tokenId != null) {
            IntegrationToken token = tokens.findById(tokenId)
                    .orElseThrow(() -> ResourceNotFoundException.of("integration_token", tokenId));
            accessScope.requireVisible("integration_token", tokenId, token.getCreditorId());
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        Instant start = from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return new UsageStore.Filter(start, end, allowed, creditorId, tokenId);
    }
}
