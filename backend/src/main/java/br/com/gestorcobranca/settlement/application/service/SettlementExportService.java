package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.SettlementExportUseCases;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.CreditorFacts;
import br.com.gestorcobranca.settlement.application.port.out.StatementDocumentRenderer;
import br.com.gestorcobranca.settlement.application.port.out.StatementDocumentRenderer.Line;
import br.com.gestorcobranca.settlement.application.port.out.StatementDocumentRenderer.Section;
import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement.Totals;
import br.com.gestorcobranca.settlement.domain.model.SettlementAdjustment;
import br.com.gestorcobranca.settlement.domain.model.SettlementExport;
import br.com.gestorcobranca.settlement.domain.repository.CommissionEntryRepository;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementStatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
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
import tools.jackson.core.type.TypeReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exportação do demonstrativo aprovado (layout em {@code docs/negocio/layout-demonstrativo-prestacao-contas.md}):
 * CSV/XLSX com um registro por recebimento, estorno, detalhe (parcela/título/componente), despesa, ajuste e
 * totais de controle — chaves para o credor conciliar com os próprios registros — e PDF resumo. Arquivo no
 * {@code FileStorage} ({@code SETTLEMENT_STATEMENT}) com SHA-256; o conteúdo depende só do demonstrativo
 * aprovado (imutável), então reexportar gera o mesmo conteúdo conciliável.
 */
@Service
@Transactional
class SettlementExportService implements SettlementExportUseCases {

    static final String[] HEADER = {"tipo_registro", "demonstrativo_id", "credor_id", "periodo_inicio", "periodo_fim",
            "chave", "alocacao_id", "pagamento_id", "acordo_id", "parcela", "titulo_credor", "componente",
            "data_pagamento", "data_competencia", "recebido_por", "meio", "tipo_recebimento", "dias_atraso", "valor",
            "honorarios_assessoria", "honorarios_credor", "base_comissao", "percentual", "comissao", "valor_fixo",
            "efeito_repasse", "regra", "versao_regra", "referencia", "observacao"};
    private static final Set<Integer> NUMERIC = Set.of(18, 19, 20, 21, 22, 23, 24, 25);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final SettlementStatementRepository statements;
    private final ReceiptRepository receipts;
    private final CommissionEntryRepository entries;
    private final SettlementFactRepository facts;
    private final SettlementSources sources;
    private final StatementDocumentRenderer renderer;
    private final FileStorage storage;
    private final SettlementFileCleanup cleanup;
    private final SettlementAccess access;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final SettlementMetrics metrics;
    private final JsonCodec json;
    private final Clock clock;

    SettlementExportService(SettlementStatementRepository statements, ReceiptRepository receipts, CommissionEntryRepository entries,
                           SettlementFactRepository facts, SettlementSources sources, StatementDocumentRenderer renderer,
                           FileStorage storage, SettlementFileCleanup cleanup,
                           SettlementAccess access, AccessScope accessScope, CurrentActor currentActor,
                           AccessLogger accessLogger, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                           SettlementMetrics metrics, JsonCodec json, Clock clock) {
        this.statements = statements;
        this.receipts = receipts;
        this.entries = entries;
        this.facts = facts;
        this.sources = sources;
        this.renderer = renderer;
        this.storage = storage;
        this.cleanup = cleanup;
        this.access = access;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public ExportView export(UUID statementId, String format) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadVisible(statementId);
        accessScope.requireCreditor(s.getCreditorId());
        Guard.requireState(s.getStatus().isClosed(), "statement.not_approved",
                "Somente demonstrativo aprovado é exportado para o credor");
        SettlementExport.Format f = SettlementAccess.parse(SettlementExport.Format.class, format, "export.format_invalid");
        List<List<String>> rows = rows(s);
        byte[] content = switch (f) {
            case CSV -> csv(rows);
            case XLSX -> xlsx(s, rows);
            case PDF -> pdf(s);
        };
        String fileName = "demonstrativo-%d-%s-a-%s.%s".formatted(s.getSequenceNumber(), s.getPeriodStart(),
                s.getPeriodEnd(), f.extension());
        FileStorage.StoredFile stored = storage.store(FileCategory.SETTLEMENT_STATEMENT, fileName, f.contentType(),
                new ByteArrayInputStream(content), content.length);
        cleanup.deleteOnRollback(stored.key());
        SettlementExport export = statements.save(new SettlementExport(s.getId(), f, stored.key(), fileName, stored.size(),
                stored.sha256(), f == SettlementExport.Format.PDF ? 0 : rows.size(), currentActor.actorId()));
        auditTrail.record(AuditEntry.of("settlement_statement.exported", "statement_export", export.getId())
                .creditor(s.getCreditorId()).created("statementId", s.getId()).created("format", f)
                .created("sha256", stored.sha256()).created("rows", export.getRowsCount()));
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(),
                Map.of("resource", SettlementStatementService.RESOURCE, "resourceId", s.getId().toString(), "format", f.name(),
                        "rows", String.valueOf(export.getRowsCount()))));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.SETTLEMENT_EXPORTED).creditor(s.getCreditorId())
                .reference(SettlementStatementService.RESOURCE, s.getId()).attr("format", f).attr("rows", export.getRowsCount())
                .attr("sequenceNumber", s.getSequenceNumber()));
        metrics.exported(f.name());
        return SettlementViews.export(export);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportView> list(UUID statementId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE);
        SettlementStatement s = loadVisible(statementId);
        return statements.findExports(s.getId()).stream().map(SettlementViews::export).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ExportFile download(UUID exportId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE);
        SettlementExport export = statements.findExport(exportId)
                .orElseThrow(() -> new ResourceNotFoundException("statement_export", exportId));
        loadVisible(export.getStatementId());
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", "statement_export", "resourceId", exportId.toString(),
                        "category", FileCategory.SETTLEMENT_STATEMENT.name())));
        return new ExportFile(export.getFileName(), export.getContentType(), export.getFileSize(), export.getSha256(),
                storage.open(export.getFileKey()));
    }

    private SettlementStatement loadVisible(UUID id) {
        SettlementStatement s = statements.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(SettlementStatementService.RESOURCE, id));
        accessScope.requireVisible(SettlementStatementService.RESOURCE, id, s.getCreditorId());
        return s;
    }

    /** Registros do arquivo conciliável (ordem estável: competência, id). */
    List<List<String>> rows(SettlementStatement s) {
        List<Receipt> included = receipts.findByStatementId(s.getId()).stream()
                .sorted(Comparator.comparing(Receipt::getCompetenceDate).thenComparing(r -> r.isPayment() ? 0 : 1)
                        .thenComparing(Receipt::getId))
                .toList();
        Map<UUID, CommissionEntry> byReceipt = entries.findByReceiptIdIn(included.stream().map(Receipt::getId).toList())
                .stream().collect(Collectors.toMap(CommissionEntry::getReceiptId, Function.identity()));
        List<List<String>> rows = new ArrayList<>();
        for (Receipt r : included) {
            CommissionEntry e = byReceipt.get(r.getId());
            String[] row = base(s, r.isPayment() ? "RECEBIMENTO" : "ESTORNO", r.getId().toString());
            row[6] = r.getAllocationId().toString();
            row[7] = str(r.getPaymentId());
            row[8] = str(r.getAgreementId());
            row[9] = installments(r);
            row[10] = nz(r.getTitleReference());
            row[12] = r.getPaidOn().toString();
            row[13] = r.getCompetenceDate().toString();
            row[14] = r.getReceivedBy().name();
            row[15] = nz(r.getMethod());
            row[16] = r.getPaymentType().name();
            row[17] = r.getDaysPastDue() == null ? "" : r.getDaysPastDue().toString();
            row[18] = plain(r.getAmount());
            row[19] = plain(e.getAgencyHonorarium());
            row[20] = plain(e.getCreditorHonorarium());
            row[21] = plain(e.getCommissionBase());
            row[22] = e.getRate().value().toPlainString();
            row[23] = plain(e.getCommissionAmount());
            row[24] = plain(e.getFixedFee());
            row[25] = plain(e.getTransferEffect());
            row[26] = e.getRuleCode();
            row[27] = String.valueOf(e.getRuleVersion());
            row[28] = str(r.getReversesReceiptId());
            row[29] = nz(r.getReversalReason());
            rows.add(Arrays.asList(row));
            for (Map<String, Object> line : lines(r)) {
                String[] detail = base(s, "DETALHE", r.getId().toString());
                detail[6] = r.getAllocationId().toString();
                detail[8] = str(r.getAgreementId());
                detail[9] = line.get("installmentNumber") == null ? "" : line.get("installmentNumber").toString();
                detail[10] = line.get("titleReference") == null ? "" : line.get("titleReference").toString();
                detail[11] = String.valueOf(line.get("component"));
                detail[18] = new BigDecimal(String.valueOf(line.get("amount"))).setScale(2).toPlainString();
                rows.add(Arrays.asList(detail));
            }
        }
        for (ReimbursableExpense e : facts.findExpensesByStatement(s.getId())) {
            String[] row = base(s, "DESPESA", e.getId().toString());
            row[11] = e.getExpenseType();
            row[13] = e.getCompetenceDate().toString();
            row[14] = e.getAdvancedBy();
            row[18] = plain(e.getAmount());
            row[25] = plain(e.deduction().negate());
            row[28] = e.getLegalExpenseId().toString();
            row[29] = e.getKind().name() + " " + e.getEffect().name() + (e.isReimbursable() ? " REEMBOLSAVEL" : "");
            rows.add(Arrays.asList(row));
        }
        for (SettlementAdjustment a : statements.findAdjustments(s.getId())) {
            if (a.getStatus() != SettlementAdjustment.Status.APPLIED) {
                continue;
            }
            String[] row = base(s, "AJUSTE", a.getId().toString());
            row[11] = a.getCategory().name();
            row[18] = plain(a.getAmount());
            row[25] = plain(a.getAmount());
            row[28] = str(a.getRelatedStatementId());
            row[29] = a.getReason();
            rows.add(Arrays.asList(row));
        }
        Totals t = s.totals();
        Money effects = included.stream().map(r -> byReceipt.get(r.getId()).getTransferEffect())
                .reduce(Money.ZERO, Money::plus);
        total(rows, s, "QTD_RECEBIMENTOS_E_ESTORNOS", BigDecimal.valueOf(included.size()));
        total(rows, s, "RECEBIDO_ASSESSORIA", t.receivedAgency().amount());
        total(rows, s, "RECEBIDO_CREDOR", t.receivedCreditor().amount());
        total(rows, s, "ESTORNOS", t.reversed().amount());
        total(rows, s, "RECUPERADO_LIQUIDO", t.recovered().amount());
        total(rows, s, "CONCILIADO", t.reconciled().amount());
        total(rows, s, "HONORARIOS_ASSESSORIA", t.agencyHonorarium().amount());
        total(rows, s, "HONORARIOS_CREDOR", t.creditorHonorarium().amount());
        total(rows, s, "COMISSAO", t.commission().amount());
        total(rows, s, "VALOR_FIXO", t.fixedFee().amount());
        total(rows, s, "SOMA_EFEITO_REPASSE_RECEBIMENTOS", effects.amount());
        total(rows, s, "DESPESAS_REEMBOLSAVEIS", t.expenses().amount());
        total(rows, s, "AJUSTES", t.adjustments().amount());
        total(rows, s, "LIQUIDO_A_REPASSAR", t.netTransfer().amount());
        return rows;
    }

    private static void total(List<List<String>> rows, SettlementStatement s, String code, BigDecimal value) {
        String[] row = base(s, "TOTAL", code);
        row[18] = value.scale() == 0 && "QTD_RECEBIMENTOS_E_ESTORNOS".equals(code) ? value.toPlainString()
                : value.setScale(2).toPlainString();
        rows.add(Arrays.asList(row));
    }

    private static String[] base(SettlementStatement s, String type, String key) {
        String[] row = new String[HEADER.length];
        Arrays.fill(row, "");
        row[0] = type;
        row[1] = s.getId().toString();
        row[2] = s.getCreditorId().toString();
        row[3] = s.getPeriodStart().toString();
        row[4] = s.getPeriodEnd().toString();
        row[5] = key;
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lines(Receipt r) {
        Object lines = json.fromJson(r.getDetail(), MAP).get("lines");
        return lines instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String installments(Receipt r) {
        return lines(r).stream().map(l -> l.get("installmentNumber")).filter(Objects::nonNull).map(Object::toString)
                .distinct().collect(Collectors.joining("|"));
    }

    private byte[] pdf(SettlementStatement s) {
        Totals t = s.totals();
        CreditorFacts creditor = sources.creditor(s.getCreditorId())
                .orElse(new CreditorFacts(s.getCreditorId(), s.getCreditorId().toString(), null));
        List<Section> sections = List.of(
                new Section("Recebimentos", List.of(
                        new Line("Recebido na conta da assessoria", t.receivedAgency().amount()),
                        new Line("Recebido diretamente pelo credor", t.receivedCreditor().amount()),
                        new Line("Estornos e realocações", t.reversed().amount()),
                        new Line("Recuperação líquida", t.recovered().amount()),
                        new Line("dos quais conciliados", t.reconciled().amount()))),
                new Section("Remuneração da assessoria", List.of(
                        new Line("Comissão sobre o recuperado", t.commission().amount()),
                        new Line("Valores fixos por acordo", t.fixedFee().amount()),
                        new Line("Honorários cobrados do devedor (assessoria)", t.agencyHonorarium().amount()),
                        new Line("Honorários repassados ao credor", t.creditorHonorarium().amount()))),
                new Section("Deduções e ajustes", List.of(
                        new Line("Custas e despesas reembolsáveis", t.expenses().amount()),
                        new Line("Ajustes", t.adjustments().amount()))),
                new Section(t.netTransfer().isNegative() ? "Resultado (a receber do credor)" : "Resultado (a repassar ao credor)",
                        List.of(new Line("Valor líquido", t.netTransfer().abs().amount()))),
                new Section("Informações do período (não são recuperação)", List.of(
                        new Line("Valor negociado em acordos formalizados", t.negotiated().amount()),
                        new Line("Comissão prevista sobre acordos em aberto", t.forecastCommission().amount()),
                        new Line("Recebido aguardando conciliação (próximo período)", t.pendingReconciliation().amount()),
                        new Line("Recebido aguardando regra contratual", t.pendingRules().amount()))));
        List<String> notes = new ArrayList<>();
        if (t.openDivergences() > 0) {
            notes.add(t.openDivergences() + " divergência(s) de conciliação em aberto no fechamento — tratadas no "
                    + "período seguinte.");
        }
        if (t.pendingReconciliation().isPositive()) {
            notes.add("Pagamentos ainda não conciliados não entram neste demonstrativo; entram no primeiro "
                    + "demonstrativo após a conciliação.");
        }
        notes.add("Estornos e correções de períodos já prestados entram no demonstrativo seguinte.");
        String sha = statements.findExports(s.getId()).stream()
                .filter(e -> e.getFormat() != SettlementExport.Format.PDF).findFirst()
                .map(SettlementExport::getSha256).orElse(null);
        return renderer.render(new StatementDocumentRenderer.Summary(creditor.legalName(), creditor.document(),
                s.getSequenceNumber(), s.getPeriodStart(), s.getPeriodEnd(), s.getStatus().name(), sections, notes, sha,
                Instant.now(clock)));
    }

    static byte[] csv(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setDelimiter(';').setRecordSeparator("\r\n").setHeader(HEADER).get())) {
            for (List<String> row : rows) {
                printer.printRecord(row.stream().map(SettlementExportService::neutralizeFormula).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] xlsx(SettlementStatement s, List<List<String>> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Demonstrativo " + s.getSequenceNumber());
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADER.length; i++) {
                header.createCell(i).setCellValue(HEADER[i]);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    String v = values.get(c);
                    if (NUMERIC.contains(c) && !v.isEmpty()) {
                        row.createCell(c).setCellValue(new BigDecimal(v).doubleValue());
                    } else {
                        row.createCell(c).setCellValue(neutralizeFormula(v));
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

    /** Evita injeção de fórmula ao abrir em planilhas (=, +, -, @ no início de texto não numérico). */
    static String neutralizeFormula(String value) {
        if (value != null && !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0
                && !value.matches("-?\\d+(\\.\\d+)?")) {
            return "'" + value;
        }
        return value;
    }

    private static String plain(Money m) {
        return m.amount().toPlainString();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
