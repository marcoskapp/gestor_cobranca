package br.com.gestorcobranca.costing.web;

import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.InvoiceView;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.ItemEstimateView;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.SaveInvoiceCommand;
import br.com.gestorcobranca.costing.application.port.in.CostRefreshUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostRefreshUseCases.RefreshResult;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.AssignUserBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.BandView;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateOverheadCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateTariffCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.FxRateView;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.OverheadView;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.SaveFxRateCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.TariffView;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.UserBandView;
import br.com.gestorcobranca.costing.application.port.in.MarginExportUseCases;
import br.com.gestorcobranca.costing.application.port.in.MarginExportUseCases.ExportFile;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginFilter;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginReport;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginTrend;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.MarginBasis;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Custos operacionais e margem por credor e carteira (contrato em docs/tecnico/desenho/margem-por-carteira.md §10.2).
 * Listas na query string com valores repetidos ({@code ?creditorId=a&creditorId=b}); datas {@code AAAA-MM-DD}; meses
 * {@code AAAA-MM} (aceita também {@code AAAA-MM-DD}, usando o mês da data).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/costing")
@Tag(name = "Custos e margem", description = "Cadastros de custo, apuração e margem por credor e carteira")
class CostingController {

    private static final String VIEW_OR_RATE = "hasAnyAuthority('COST_VIEW', 'COST_RATE_MANAGE')";
    private static final String VIEW_OR_MANAGE = "hasAnyAuthority('COST_VIEW', 'COST_MANAGE')";
    private static final String RATE = "hasAuthority('COST_RATE_MANAGE')";
    private static final String MANAGE = "hasAuthority('COST_MANAGE')";

    private final CostSettingsUseCases settings;
    private final MarginQueries margins;
    private final MarginExportUseCases exports;
    private final CostRefreshUseCases refresh;
    private final ChannelInvoiceUseCases invoices;

    CostingController(CostSettingsUseCases settings, MarginQueries margins, MarginExportUseCases exports,
                      CostRefreshUseCases refresh, ChannelInvoiceUseCases invoices) {
        this.settings = settings;
        this.margins = margins;
        this.exports = exports;
        this.refresh = refresh;
        this.invoices = invoices;
    }

    // ------------------------------------------------------------------ margem

    @GetMapping("/margin")
    @PreAuthorize("hasAuthority('COST_VIEW')")
    @Operation(summary = "Margem por credor (com carteiras) e por mês, com os avisos da apuração; basis=ESTIMATED "
            + "(padrão) ou RECONCILED (custos de canal com o ajuste pela fatura)")
    MarginReport margin(@Valid MarginParams params) {
        return margins.margin(params.toFilter());
    }

    @GetMapping("/margin/trend")
    @PreAuthorize("hasAuthority('COST_VIEW')")
    @Operation(summary = "Tendência dos 6 meses que terminam no mês do fim do período, por credor e carteira")
    MarginTrend marginTrend(@Valid MarginParams params) {
        return margins.trend(params.toFilter());
    }

    @GetMapping("/margin/export")
    @PreAuthorize("hasAuthority('COST_VIEW') and hasAuthority('REPORT_EXPORT')")
    @Operation(summary = "Exporta a margem (xlsx ou csv) com os mesmos filtros; registrado no log de acesso")
    ResponseEntity<InputStreamResource> export(@Valid MarginParams params, @RequestParam String format) {
        ExportFile file = exports.export(params.toFilter(), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAnyAuthority('COST_MANAGE', 'COST_RATE_MANAGE')")
    @Operation(summary = "Reapura os custos do período (ex.: depois de corrigir faixa, tarifa ou câmbio)")
    RefreshResult refresh(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return refresh.refresh(from, to);
    }

    // ------------------------------------------------------------------ faixas de custo de pessoas

    @GetMapping("/bands")
    @PreAuthorize(VIEW_OR_RATE)
    List<BandView> bands() {
        return settings.bands();
    }

    @PostMapping("/bands")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(RATE)
    @Operation(summary = "Nova faixa ou nova vigência de uma faixa (encerra a vigência anterior na véspera)")
    BandView createBand(@Valid @RequestBody BandRequest r) {
        return settings.createBand(new CreateBandCommand(r.name(), r.hourlyCost(), r.validFrom()));
    }

    @PostMapping("/bands/{id}/end")
    @PreAuthorize(RATE)
    BandView endBand(@PathVariable UUID id, @Valid @RequestBody EndRequest r) {
        return settings.endBand(id, r.validTo());
    }

    @GetMapping("/user-bands")
    @PreAuthorize(RATE)
    @Operation(summary = "Pessoas da assessoria com a faixa vigente hoje (sem valor-hora)")
    List<UserBandView> userBands() {
        return settings.userBands();
    }

    @GetMapping("/user-bands/{userId}")
    @PreAuthorize(RATE)
    @Operation(summary = "Histórico das faixas de uma pessoa")
    List<UserBandView> userBandHistory(@PathVariable UUID userId) {
        return settings.userBandHistory(userId);
    }

    @PutMapping("/user-bands/{userId}")
    @PreAuthorize(RATE)
    @Operation(summary = "Faixa da pessoa a partir de uma data (faixa vazia = sem faixa a partir da data)")
    UserBandView assignUserBand(@PathVariable UUID userId, @Valid @RequestBody UserBandRequest r) {
        return settings.assignUserBand(userId, new AssignUserBandCommand(r.bandName(), r.validFrom()));
    }

    // ------------------------------------------------------------------ tarifas

    @GetMapping("/tariffs")
    @PreAuthorize(VIEW_OR_MANAGE)
    List<TariffView> tariffs() {
        return settings.tariffs();
    }

    @PostMapping("/tariffs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    TariffView createTariff(@Valid @RequestBody TariffRequest r) {
        return settings.createTariff(new CreateTariffCommand(r.channel(), r.unitCost(), r.validFrom()));
    }

    @PostMapping("/tariffs/{id}/end")
    @PreAuthorize(MANAGE)
    TariffView endTariff(@PathVariable UUID id, @Valid @RequestBody EndRequest r) {
        return settings.endTariff(id, r.validTo());
    }

    // ------------------------------------------------------------------ câmbio

    @GetMapping("/fx-rates")
    @PreAuthorize(VIEW_OR_MANAGE)
    List<FxRateView> fxRates() {
        return settings.fxRates();
    }

    @PostMapping("/fx-rates")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Câmbio USD→BRL do mês (substitui o do mês, se já informado)")
    FxRateView saveFxRate(@Valid @RequestBody FxRateRequest r) {
        return settings.saveFxRate(new SaveFxRateCommand(month(r.month()), r.usdBrl()));
    }

    // ------------------------------------------------------------------ custos fixos

    @GetMapping("/overheads")
    @PreAuthorize(VIEW_OR_MANAGE)
    List<OverheadView> overheads(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to) {
        return settings.overheads(from == null ? null : month(from), to == null ? null : month(to));
    }

    @PostMapping("/overheads")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Custo fixo do mês com a regra de rateio (padrão: horas); o rateio do mês é refeito na hora")
    OverheadView createOverhead(@Valid @RequestBody OverheadRequest r) {
        return settings.createOverhead(new CreateOverheadCommand(month(r.month()), r.description(), r.amount(),
                r.allocation(), r.creditorId()));
    }

    @DeleteMapping("/overheads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Exclui o custo fixo (qualquer mês) e refaz o rateio do mês; motivo opcional na auditoria")
    void deleteOverhead(@PathVariable UUID id, @RequestParam(required = false) @Size(max = 500) String reason) {
        settings.deleteOverhead(id, reason);
    }

    // ------------------------------------------------------------------ faturas de canal (conciliação, §13)

    @GetMapping("/invoices")
    @PreAuthorize(VIEW_OR_MANAGE)
    @Operation(summary = "Faturas de canal dos meses (AAAA-MM; sem os dois = últimos 24 meses), com a estimativa do item")
    List<InvoiceView> invoices(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return invoices.invoices(from == null ? null : month(from), to == null ? null : month(to));
    }

    @GetMapping("/invoices/estimates")
    @PreAuthorize(VIEW_OR_MANAGE)
    @Operation(summary = "Estimativa de cada item de canal por mês (assessoria inteira), com a fatura quando houver")
    List<ItemEstimateView> invoiceEstimates(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return invoices.estimates(from == null ? null : month(from), to == null ? null : month(to));
    }

    @PostMapping("/invoices")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Informa a fatura do mês × item (substitui a já informada); o ajuste do mês é refeito na hora")
    InvoiceView saveInvoice(@Valid @RequestBody InvoiceRequest r) {
        return invoices.save(new SaveInvoiceCommand(month(r.month()), r.item(), r.amount(), r.supplier(), r.note()));
    }

    @DeleteMapping("/invoices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Exclui a fatura e refaz o ajuste do mês; motivo opcional na auditoria")
    void deleteInvoice(@PathVariable UUID id, @RequestParam(required = false) @Size(max = 500) String reason) {
        invoices.delete(id, reason);
    }

    // ------------------------------------------------------------------ requisições

    static YearMonth month(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException("costing.month_required", "Informe o mês");
        }
        String v = value.trim();
        try {
            return v.length() > 7 ? YearMonth.from(LocalDate.parse(v)) : YearMonth.parse(v);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("costing.month_invalid", "Mês inválido (use AAAA-MM)");
        }
    }

    record MarginParams(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @Size(max = 200) Set<UUID> creditorId, @Size(max = 500) Set<UUID> portfolioId,
                        MarginBasis basis) {

        MarginFilter toFilter() {
            return new MarginFilter(from, to, creditorId, portfolioId, basis);
        }
    }

    record BandRequest(@NotNull @Size(max = 80) String name, @NotNull BigDecimal hourlyCost,
                       @NotNull LocalDate validFrom) {
    }

    record EndRequest(@NotNull LocalDate validTo) {
    }

    record UserBandRequest(@Size(max = 80) String bandName, @NotNull LocalDate validFrom) {
    }

    record TariffRequest(@NotNull TariffChannel channel, @NotNull BigDecimal unitCost, @NotNull LocalDate validFrom) {
    }

    record FxRateRequest(@NotNull String month, @NotNull BigDecimal usdBrl) {
    }

    record InvoiceRequest(@NotNull String month, @NotNull ChannelItem item, @NotNull BigDecimal amount,
                          @Size(max = 120) String supplier, @Size(max = 500) String note) {
    }

    record OverheadRequest(@NotNull String month, @NotNull @Size(max = 200) String description,
                           @NotNull BigDecimal amount, OverheadAllocation allocation, UUID creditorId) {
    }
}
