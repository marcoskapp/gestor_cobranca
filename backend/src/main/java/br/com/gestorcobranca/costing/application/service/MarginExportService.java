package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases;
import br.com.gestorcobranca.costing.application.port.in.MarginExportUseCases;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginFilter;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginReport;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.export.SpreadsheetSafety;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exportação do relatório de margem (desenho §6.2): XLSX com as abas resumo, por credor, por carteira, por mês e
 * premissas (faixas pelo nome, com o valor-hora só para quem tem {@code COST_RATE_MANAGE}; tarifas, câmbio, custos
 * fixos e regras de rateio — nunca a faixa de uma pessoa) e CSV no formato longo (seção, chave, rótulo, indicador, valor). Mesmos dados e escopo do painel; o arquivo
 * é gerado na hora e não é guardado. O log de acesso registra filtros, linhas e o SHA-256 do arquivo
 * ({@code DATA_EXPORTED}).
 */
@Service
@Transactional(readOnly = true)
class MarginExportService implements MarginExportUseCases {

    static final int MAX_ROWS = 100_000;
    /** BOM para o Excel reconhecer UTF-8 ao abrir o CSV com duplo clique. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    static final List<String> CSV_HEADER = List.of("secao", "chave", "rotulo", "indicador", "valor");

    private static final Map<CostCategory, String> CATEGORY_LABELS = Map.of(
            CostCategory.HUMAN_DIRECT, "Pessoas (tempo direto)",
            CostCategory.HUMAN_IDLE, "Ociosidade",
            CostCategory.AI, "IA",
            CostCategory.TELEPHONY, "Telefonia",
            CostCategory.MESSAGING, "Mensagens",
            CostCategory.VARIABLE_COMMISSION, "Comissão variável",
            CostCategory.LEGAL, "Jurídico",
            CostCategory.OVERHEAD, "Custos fixos (rateio)");

    private static final Map<TariffChannel, String> CHANNEL_LABELS = Map.of(
            TariffChannel.WHATSAPP_UTILITY, "WhatsApp — utilidade (por conversa de 24 h)",
            TariffChannel.WHATSAPP_MARKETING, "WhatsApp — marketing (por conversa de 24 h)",
            TariffChannel.WHATSAPP_AUTHENTICATION, "WhatsApp — autenticação (por conversa de 24 h)",
            TariffChannel.SMS, "SMS (por mensagem)",
            TariffChannel.EMAIL, "E-mail (por mensagem)");

    private static final Map<OverheadAllocation, String> ALLOCATION_LABELS = Map.of(
            OverheadAllocation.HOURS, "Horas humanas (diretas + ociosas)",
            OverheadAllocation.RECOVERED, "Valor recuperado bruto",
            OverheadAllocation.CASES, "Casos trabalhados (caso × dia)",
            OverheadAllocation.CREDITOR, "Direto ao credor");

    /** Avisos em português (o código fica só na coluna "chave" do CSV). Sem rótulo conhecido: texto genérico. */
    private static final Map<String, String> WARNING_LABELS = Map.of(
            "HOURS_WITHOUT_BAND", "Horas de trabalho sem faixa de custo (entraram com custo zero)",
            "MISSING_FX_RATE", "Meses sem câmbio do dólar (a IA usou o último câmbio informado)",
            "NO_FX_RATE", "Meses sem nenhum câmbio do dólar (custo de IA zerado)",
            "MISSING_TARIFF", "Unidades de uso sem tarifa (entraram com custo zero)",
            "OVERHEAD_WITHOUT_BASIS", "Custos fixos sem base de rateio no mês (ficaram sem credor)",
            "NOT_REFRESHED", "Dias do período ainda não apurados");

    private static final List<String> RULES = List.of(
            "Receita = comissão + valor fixo + honorários da assessoria sobre o recebido no período (líquido de estornos).",
            "Pessoas (tempo direto) = duração das interações de pessoas + pós-atendimento das chamadas × valor-hora da "
                    + "faixa vigente da pessoa no dia.",
            "Ociosidade = tempo logado − tempo direto da pessoa no dia, rateado pelos credores e carteiras que ela "
                    + "trabalhou no dia; sem trabalho direto no dia, fica sem credor.",
            "IA = custo do assistente e da Importação PDF em dólar × câmbio do mês (sem câmbio do mês, o último anterior).",
            "Telefonia = minutos tarifados (arredondados para cima por chamada atendida) × tarifa da conta usada.",
            "Mensagens = conversas de WhatsApp iniciadas pela empresa (uma por janela de 24 h por categoria) e "
                    + "mensagens de SMS e e-mail enviadas × tarifa do canal.",
            "Comissão variável = créditos da remuneração variável dos negociadores (fora os dispensados).",
            "Jurídico = custas adiantadas pela assessoria e não reembolsáveis.",
            "Custos fixos = custo do mês rateado pela regra do item, nos dias do mês.",
            "Custo direto = pessoas (direto) + IA + telefonia + mensagens + comissão variável + jurídico.",
            "Margem de contribuição = receita − custo direto; margem líquida = margem de contribuição − ociosidade − "
                    + "custos fixos.",
            "ROI operacional = margem líquida ÷ custo total; custo por real recuperado = custo total ÷ recuperado; "
                    + "custo por acordo cumprido = custo total ÷ acordos quitados. Sem base, a taxa fica vazia.",
            "Base dos custos: estimada (uso × tarifa); a conciliação com a fatura real é da fase 2.");

    private static final List<String> LINE_HEADER = buildLineHeader();

    private final MarginQueries queries;
    private final CostSettingsUseCases settings;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;
    private final CostingMetrics metrics;
    private final Clock clock;

    MarginExportService(MarginQueries queries, CostSettingsUseCases settings, CurrentActor currentActor,
                        AccessScope accessScope, AccessLogger accessLogger, CostingMetrics metrics, Clock clock) {
        this.queries = queries;
        this.settings = settings;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Uma aba (cabeçalho e linhas) e as mesmas linhas no formato longo do CSV. */
    private record Table(String sheet, List<String> header, List<List<Object>> rows) {
    }

    @Override
    public ExportFile export(MarginFilter filter, String format) {
        accessScope.requirePermission(Permission.COST_VIEW);
        accessScope.requirePermission(Permission.REPORT_EXPORT);
        String fmt = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        Guard.require(fmt.equals("CSV") || fmt.equals("XLSX"), "costing.export_format_invalid",
                "Formato de exportação inválido (use csv ou xlsx)");
        MarginReport report = queries.margin(filter);
        List<Table> tables = tables(report);
        List<List<Object>> longRows = longFormat(report);
        int rows = fmt.equals("CSV") ? longRows.size() : tables.stream().mapToInt(t -> t.rows().size()).sum();
        Guard.require(rows <= MAX_ROWS, "costing.export_too_large",
                "Exportação acima de " + MAX_ROWS + " linhas: reduza o período ou os filtros");
        byte[] content = fmt.equals("CSV") ? csv(longRows) : xlsx(tables);
        String sha256 = sha256(content);
        String fileName = "margem-por-carteira-%s-a-%s.%s".formatted(report.period().from(), report.period().to(),
                fmt.toLowerCase(Locale.ROOT));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", "costing_margin");
        details.put("format", fmt);
        details.put("from", report.period().from().toString());
        details.put("to", report.period().to().toString());
        details.put("creditors", String.valueOf(filter == null ? 0 : filter.creditorIds().size()));
        details.put("portfolios", String.valueOf(filter == null ? 0 : filter.portfolioIds().size()));
        details.put("rows", String.valueOf(rows));
        details.put("sha256", sha256);
        details.put("generatedOn", LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).toString());
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(), details));
        metrics.exported(fmt, rows);
        String contentType = fmt.equals("CSV") ? "text/csv; charset=UTF-8"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return new ExportFile(fileName, contentType, content.length, sha256, new ByteArrayInputStream(content));
    }

    // ------------------------------------------------------------------ abas

    private List<Table> tables(MarginReport r) {
        List<Table> tables = new ArrayList<>();
        List<List<Object>> summary = new ArrayList<>();
        summary.add(List.of("Período", r.period().from() + " a " + r.period().to()));
        summary.add(List.of("Base dos custos", "Estimada (uso × tarifa)"));
        summary.add(List.of("Apurado até", r.computedUntil() == null ? "ainda não apurado" : r.computedUntil().toString()));
        summary.add(List.of("Escopo", r.unallocated() == null ? "credores do filtro ou do escopo do usuário"
                : "assessoria inteira (inclui valores sem credor)"));
        indicatorRows(r.totals()).forEach(summary::add);
        r.warnings().forEach(w -> summary.add(List.of("Aviso: " + warningLabel(w.code()), w.count())));
        tables.add(new Table("Resumo", List.of("Indicador", "Valor"), summary));

        List<List<Object>> byCreditor = new ArrayList<>();
        r.creditors().forEach(c -> byCreditor.add(lineRow(List.of(text(c.name())), c.line())));
        if (r.unallocated() != null) {
            byCreditor.add(lineRow(List.of("Sem credor"), r.unallocated()));
        }
        byCreditor.add(lineRow(List.of("Total"), r.totals()));
        tables.add(new Table("Por credor", prepend(List.of("Credor")), byCreditor));

        List<List<Object>> byPortfolio = new ArrayList<>();
        r.creditors().forEach(c -> c.portfolios().forEach(p -> byPortfolio.add(lineRow(List.of(text(c.name()),
                p.portfolioId() == null ? "Sem carteira" : text(p.name())), p.line()))));
        tables.add(new Table("Por carteira", prepend(List.of("Credor", "Carteira")), byPortfolio));

        List<List<Object>> byMonth = new ArrayList<>();
        r.months().forEach(m -> byMonth.add(lineRow(List.of(m.month()), m.line())));
        tables.add(new Table("Por mês", prepend(List.of("Mês")), byMonth));

        tables.add(new Table("Premissas", List.of("Seção", "Item", "Valor", "Início", "Fim"), assumptions()));
        return tables;
    }

    private List<List<Object>> assumptions() {
        List<List<Object>> rows = new ArrayList<>();
        // valor-hora só para quem gerencia as faixas (COST_RATE_MANAGE); os demais veem só os nomes (LGPD, RC-07)
        boolean rates = currentActor.get().has(Permission.COST_RATE_MANAGE);
        settings.bands().forEach(b -> rows.add(rates && b.hourlyCost() != null
                ? row("Faixa de custo de pessoas (R$/hora)", b.name(), b.hourlyCost(), b.validFrom(), b.validTo())
                : row("Faixa de custo de pessoas", b.name(), null, b.validFrom(), b.validTo())));
        settings.tariffs().forEach(t -> rows.add(row("Tarifa de canal (R$/unidade)", CHANNEL_LABELS.get(t.channel()),
                t.unitCost(), t.validFrom(), t.validTo())));
        settings.fxRates().forEach(f -> rows.add(row("Câmbio USD→BRL", f.month(), f.usdBrl(), null, null)));
        settings.overheads(null, null).forEach(o -> rows.add(row("Custo fixo (" + o.month() + ")",
                o.description() + " — " + ALLOCATION_LABELS.get(o.allocation())
                        + (o.creditorName() == null ? "" : " — " + o.creditorName()), o.amount(), null, null)));
        RULES.forEach(rule -> rows.add(row("Regra", rule, null, null, null)));
        return rows;
    }

    private static List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>();
        for (Object v : values) {
            row.add(v instanceof LocalDate d ? d.toString() : v);
        }
        return row;
    }

    private static List<String> buildLineHeader() {
        List<String> h = new ArrayList<>(List.of("Recuperado", "Receita"));
        for (CostCategory c : CostCategory.values()) {
            h.add(CATEGORY_LABELS.get(c));
        }
        h.addAll(List.of("Custo direto", "Custo total", "Margem de contribuição", "Margem líquida",
                "Margem de contribuição (fração)", "Margem líquida (fração)", "ROI operacional",
                "Custo por real recuperado", "Acordos quitados", "Custo por acordo cumprido", "Horas diretas",
                "Horas ociosas"));
        return List.copyOf(h);
    }

    private static List<String> prepend(List<String> keys) {
        List<String> h = new ArrayList<>(keys);
        h.addAll(LINE_HEADER);
        return h;
    }

    private static List<Object> lineRow(List<Object> keys, MarginLine l) {
        List<Object> row = new ArrayList<>(keys);
        row.add(l.recovered());
        row.add(l.revenue());
        for (CostCategory c : CostCategory.values()) {
            row.add(l.costs().get(c));
        }
        row.addAll(java.util.Arrays.asList(l.directCost(), l.totalCost(), l.contributionMargin(), l.netMargin(),
                l.contributionMarginRate(), l.netMarginRate(), l.roi(), l.costPerRecovered(),
                BigDecimal.valueOf(l.settledAgreements()), l.costPerSettledAgreement(), l.directHours(),
                l.idleHours()));
        return row;
    }

    private static List<List<Object>> indicatorRows(MarginLine l) {
        List<List<Object>> rows = new ArrayList<>();
        List<Object> values = lineRow(List.of(), l);
        for (int i = 0; i < LINE_HEADER.size(); i++) {
            List<Object> row = new ArrayList<>();
            row.add(LINE_HEADER.get(i));
            row.add(values.get(i));
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ formato longo (CSV)

    private List<List<Object>> longFormat(MarginReport r) {
        List<List<Object>> rows = new ArrayList<>();
        longLine(rows, "totais", "ALL", "Total", r.totals());
        if (r.unallocated() != null) {
            longLine(rows, "sem_credor", "NONE", "Sem credor", r.unallocated());
        }
        r.creditors().forEach(c -> {
            longLine(rows, "por_credor", String.valueOf(c.creditorId()), text(c.name()), c.line());
            c.portfolios().forEach(p -> longLine(rows, "por_carteira",
                    c.creditorId() + "/" + (p.portfolioId() == null ? "NONE" : p.portfolioId()),
                    text(c.name()) + " / " + (p.portfolioId() == null ? "Sem carteira" : text(p.name())), p.line()));
        });
        r.months().forEach(m -> longLine(rows, "por_mes", m.month(), m.month(), m.line()));
        r.warnings().forEach(w -> rows.add(List.of("avisos", w.code(), warningLabel(w.code()), "quantidade",
                w.count())));
        boolean rates = currentActor.get().has(Permission.COST_RATE_MANAGE);
        settings.bands().forEach(b -> {
            boolean value = rates && b.hourlyCost() != null;
            rows.add(new ArrayList<>(java.util.Arrays.asList("premissas_faixas", b.name() + "@" + b.validFrom(),
                    b.name(), value ? "valor_hora" : "inicio_vigencia",
                    value ? b.hourlyCost() : b.validFrom().toString())));
        });
        settings.tariffs().forEach(t -> rows.add(new ArrayList<>(java.util.Arrays.asList("premissas_tarifas",
                t.channel() + "@" + t.validFrom(), CHANNEL_LABELS.get(t.channel()), "tarifa", t.unitCost()))));
        settings.fxRates().forEach(f -> rows.add(new ArrayList<>(java.util.Arrays.asList("premissas_cambio", f.month(),
                f.month(), "usd_brl", f.usdBrl()))));
        settings.overheads(null, null).forEach(o -> rows.add(new ArrayList<>(java.util.Arrays.asList(
                "premissas_custos_fixos", o.month() + "/" + o.id(), o.description(),
                "valor_" + o.allocation().name().toLowerCase(Locale.ROOT), o.amount()))));
        return rows;
    }

    private static void longLine(List<List<Object>> rows, String section, String key, String label, MarginLine l) {
        List<Object> values = lineRow(List.of(), l);
        for (int i = 0; i < LINE_HEADER.size(); i++) {
            List<Object> row = new ArrayList<>();
            row.add(section);
            row.add(key);
            row.add(label);
            row.add(indicatorKey(LINE_HEADER.get(i)));
            row.add(values.get(i));
            rows.add(row);
        }
    }

    /** Chave estável do indicador no formato longo (sem acentos nem espaços). */
    static String indicatorKey(String label) {
        String plain = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    static String warningLabel(String code) {
        return WARNING_LABELS.getOrDefault(code, "Aviso da apuração");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ escrita

    static byte[] csv(List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader(CSV_HEADER.toArray(String[]::new)).get())) {
            for (List<Object> row : rows) {
                printer.printRecord(row.stream().map(MarginExportService::cell).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] xlsx(List<Table> tables) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Table t : tables) {
                Sheet sheet = workbook.createSheet(t.sheet());
                Row header = sheet.createRow(0);
                for (int i = 0; i < t.header().size(); i++) {
                    header.createCell(i).setCellValue(t.header().get(i));
                }
                for (int r = 0; r < t.rows().size(); r++) {
                    Row row = sheet.createRow(r + 1);
                    List<Object> values = t.rows().get(r);
                    for (int c = 0; c < values.size(); c++) {
                        Object v = values.get(c);
                        if (v instanceof BigDecimal number) {
                            row.createCell(c).setCellValue(number.doubleValue());
                        } else if (v != null) {
                            // texto sempre como célula de texto (nunca fórmula) e neutralizado
                            row.createCell(c).setCellValue(SpreadsheetSafety.neutralize(v.toString()));
                        }
                    }
                }
            }
            workbook.write(out);
            workbook.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        return SpreadsheetSafety.neutralize(value.toString());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
