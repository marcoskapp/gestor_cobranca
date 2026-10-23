package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import br.com.gestorcobranca.incentive.application.port.in.StatementExportUseCases;
import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.model.statement.ExportFormat;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementExport;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementStatus;
import br.com.gestorcobranca.incentive.domain.repository.StatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exportação para a folha (§5.7): todos os extratos aprovados do período num arquivo CSV ou XLSX,
 * guardado com hash como evidência; os extratos aprovados passam a {@code EXPORTED}.
 */
@Service
@Transactional
class StatementExportService implements StatementExportUseCases {

    private static final String[] HEADER = {"periodo", "negociador_id", "negociador", "plano", "versao_plano",
            "resultado", "recuperado_creditado", "variavel_calculado", "ajustes", "valor_a_pagar", "status", "extrato_id"};

    private final StatementRepository statements;
    private final Workforce workforce;
    private final FileStorage storage;
    private final IncentiveAccess access;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final IncentiveMetrics metrics;
    private final Clock clock;

    StatementExportService(StatementRepository statements, Workforce workforce, FileStorage storage, IncentiveAccess access,
                           AccessLogger accessLogger, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                           IncentiveMetrics metrics, Clock clock) {
        this.statements = statements;
        this.workforce = workforce;
        this.storage = storage;
        this.access = access;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public ExportFile export(YearMonth period, String format) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_APPROVE);
        Guard.require(period != null, "export.period_required", "Informe o período");
        ExportFormat exportFormat = StatementService.enumOf(ExportFormat.class, format == null ? "" : format,
                "export.format_invalid");
        List<CommissionStatement> approved = statements.findByPeriodAndStatus(period.atDay(1),
                        List.of(StatementStatus.APPROVED, StatementStatus.EXPORTED)).stream()
                .sorted(Comparator.comparing(CommissionStatement::getBeneficiaryId)).toList();
        Guard.require(!approved.isEmpty(), "export.no_approved_statements", "Nenhum extrato aprovado no período");
        Map<UUID, WorkforceMember> members = workforce.members(approved.stream().map(CommissionStatement::getBeneficiaryId)
                .toList());
        List<List<String>> rows = approved.stream().map(s -> row(period, s, members.get(s.getBeneficiaryId()))).toList();
        byte[] content = exportFormat == ExportFormat.CSV ? csv(rows) : xlsx(period, rows);
        String fileName = "comissao-negociadores-%s.%s".formatted(period, exportFormat.extension());
        FileStorage.StoredFile stored = storage.store(FileCategory.REPORT_EXPORT, fileName, exportFormat.contentType(),
                new ByteArrayInputStream(content), content.length);
        Money total = approved.stream().map(CommissionStatement::getPayableAmount).reduce(Money.ZERO, Money::plus);
        StatementExport export = statements.saveExport(new StatementExport(period.atDay(1), exportFormat, stored.key(),
                fileName, stored.size(), stored.sha256(), approved.size(), total, access.actor().id()));
        Instant now = Instant.now(clock);
        for (CommissionStatement statement : approved) {
            statement.markExported(export.getId(), now);
            statements.save(statement);
        }
        auditTrail.record(AuditEntry.of("commission_statement.exported", "statement_export", export.getId())
                .created("period", period.toString()).created("format", exportFormat.name())
                .created("statements", approved.size()).created("sha256", stored.sha256()));
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, access.actor(),
                Map.of("resource", "commission_statement", "period", period.toString(), "format", exportFormat.name(),
                        "rows", String.valueOf(approved.size()))));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_STATEMENT_EXPORTED)
                .reference("statement_export", export.getId()).amount(CommissionBands.aggregate(total, approved.size()))
                .attr("totalBand", CommissionBands.of(total))
                .attr("period", period.toString()).attr("format", exportFormat).attr("statements", approved.size()));
        metrics.exported(exportFormat.name());
        return new ExportFile(fileName, exportFormat.contentType(), content.length, new ByteArrayInputStream(content));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportView> list(YearMonth period) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_APPROVE);
        return statements.findExports(period.atDay(1)).stream().map(StatementExportService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ExportFile download(UUID exportId) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_APPROVE);
        StatementExport export = statements.findExport(exportId)
                .orElseThrow(() -> ResourceNotFoundException.of("statement_export", exportId));
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, access.actor(),
                Map.of("resource", "statement_export", "resourceId", exportId.toString())));
        return new ExportFile(export.getFileName(), export.getContentType(), export.getFileSize(),
                storage.open(export.getFileKey()));
    }

    private static List<String> row(YearMonth period, CommissionStatement s, WorkforceMember member) {
        return List.of(period.toString(), s.getBeneficiaryId().toString(), member == null ? "" : member.name(),
                s.getPlanCode() == null ? "" : s.getPlanCode(),
                s.getPlanVersion() == null ? "" : s.getPlanVersion().toString(), s.getOutcome().name(),
                s.getCreditedRecovery().amount().toPlainString(), s.getCalculatedAmount().amount().toPlainString(),
                s.getAdjustmentsAmount().amount().toPlainString(), s.getPayableAmount().amount().toPlainString(),
                "EXPORTED", s.getId().toString());
    }

    private static byte[] csv(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader(HEADER).get())) {
            for (List<String> row : rows) {
                printer.printRecord(row.stream().map(StatementExportService::neutralizeFormula).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] xlsx(YearMonth period, List<List<String>> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Comissao " + period);
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADER.length; i++) {
                header.createCell(i).setCellValue(HEADER[i]);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    boolean numeric = c >= 6 && c <= 9;
                    if (numeric) {
                        row.createCell(c).setCellValue(Double.parseDouble(values.get(c)));
                    } else {
                        row.createCell(c).setCellValue(values.get(c));
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

    /** Evita injeção de fórmula ao abrir o CSV em planilhas (=, +, -, @ no início). */
    private static String neutralizeFormula(String value) {
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("-?\\d+(\\.\\d+)?")) {
            return "'" + value;
        }
        return value;
    }

    private static ExportView view(StatementExport e) {
        return new ExportView(e.getId(), YearMonth.from(e.getPeriodStart()), e.getFormat().name(), e.getFileName(),
                e.getFileSize(), e.getSha256(), e.getStatementsCount(), e.getTotalAmount().amount(), e.getExportedBy(),
                e.getCreatedAt());
    }
}
