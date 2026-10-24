package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.AnalyticsProperties;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsExportUseCases;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import br.com.gestorcobranca.analytics.domain.service.SpreadsheetSafety;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
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
 * Exportação dos relatórios no formato longo de {@link ReportTable}. O arquivo é gerado na hora,
 * a partir dos mesmos dados e escopo da tela, e não é guardado (é reproduzível pelos filtros e pelo
 * carimbo "dados até"; guardar cópias multiplicaria dados da operação). O log de acesso registra
 * relatório, filtros, linhas e o SHA-256 do arquivo entregue.
 */
@Service
@Transactional(readOnly = true)
class AnalyticsExportService implements AnalyticsExportUseCases {

    /** BOM para o Excel reconhecer UTF-8 ao abrir o CSV com duplo clique. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final AnalyticsQueries queries;
    private final AnalyticsAccess access;
    private final AccessLogger accessLogger;
    private final AnalyticsMetrics metrics;
    private final AnalyticsProperties properties;
    private final Clock clock;

    AnalyticsExportService(AnalyticsQueries queries, AnalyticsAccess access, AccessLogger accessLogger,
                           AnalyticsMetrics metrics, AnalyticsProperties properties, Clock clock) {
        this.queries = queries;
        this.access = access;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ExportFile export(String report, ReportFilter filter, String groupBy, String format) {
        access.requireAny("analytics_export", Permission.REPORT_EXPORT);
        String fmt = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        Guard.require(fmt.equals("CSV") || fmt.equals("XLSX"), "analytics.export_format_invalid",
                "Formato de exportação inválido (use CSV ou XLSX)");
        String name = report == null ? "" : report.trim().toLowerCase(Locale.ROOT);
        ReportTable table = switch (name) {
            case "portfolio" -> ReportTable.of(queries.portfolio(filter));
            case "recovery" -> ReportTable.of(queries.recovery(filter, groupBy));
            case "conversion" -> ReportTable.of(queries.conversion(filter, groupBy));
            case "productivity" -> ReportTable.of(queries.productivity(filter, groupBy));
            case "reliability" -> ReportTable.of(queries.reliability(filter));
            default -> throw new BusinessRuleException("analytics.report_invalid", "Relatório desconhecido: " + report);
        };
        List<List<Object>> rows = new ArrayList<>();
        for (ReportTable.Line l : table.lines()) {
            List<Object> row = new ArrayList<>();
            row.add(l.section());
            row.add(l.key());
            row.add(l.label());
            row.add(l.indicator());
            row.add(l.value());
            row.add(l.numerator());
            row.add(l.denominator());
            row.add(l.note());
            rows.add(row);
        }
        Guard.require(rows.size() <= properties.exportMaxRows(), "analytics.export_too_large",
                "Exportação acima de " + properties.exportMaxRows() + " linhas: reduza o período ou os filtros");
        byte[] content = fmt.equals("CSV") ? csv(rows) : xlsx(name, rows);
        String sha256 = sha256(content);
        String fileName = "relatorio-%s-%s-a-%s.%s".formatted(name, filter.from(), filter.to(),
                fmt.toLowerCase(Locale.ROOT));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", "analytics_report");
        details.put("report", name);
        details.put("format", fmt);
        details.put("groupBy", groupBy == null ? "NONE" : groupBy);
        details.put("from", filter.from().toString());
        details.put("to", filter.to().toString());
        details.put("creditors", String.valueOf(filter.creditorIds().size()));
        details.put("operators", String.valueOf(filter.operatorIds().size()));
        details.put("teams", String.valueOf(filter.teamIds().size()));
        details.put("rows", String.valueOf(rows.size()));
        details.put("sha256", sha256);
        details.put("generatedOn", LocalDate.now(clock).toString());
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, access.actor(), details));
        metrics.exported(name, fmt, rows.size());
        String contentType = fmt.equals("CSV") ? "text/csv; charset=UTF-8"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return new ExportFile(fileName, contentType, content.length, sha256, new ByteArrayInputStream(content));
    }

    static byte[] csv(List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader(ReportTable.HEADER.toArray(String[]::new)).get())) {
            for (List<Object> row : rows) {
                printer.printRecord(row.stream().map(AnalyticsExportService::text).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] xlsx(String report, List<List<Object>> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(report);
            Row header = sheet.createRow(0);
            for (int i = 0; i < ReportTable.HEADER.size(); i++) {
                header.createCell(i).setCellValue(ReportTable.HEADER.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
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
            workbook.write(out);
            workbook.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(Object value) {
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
