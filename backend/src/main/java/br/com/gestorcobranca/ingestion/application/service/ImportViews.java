package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.Counters;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.ColumnCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.ImportLayoutView;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportRowIssue;
import br.com.gestorcobranca.ingestion.domain.model.LayoutColumn;

import java.util.Comparator;

/** Conversão de entidades em views do módulo. */
final class ImportViews {

    private ImportViews() {
    }

    static ImportBatchView batch(ImportBatch b) {
        Counters counters = new Counters(b.getRowsRead(), b.getRowsValid(), b.getRowsWithError(), b.getErrors(),
                b.getWarnings(), b.getDebtorsCreated(), b.getDebtorsUpdated(), b.getTitlesCreated(), b.getTitlesUpdated(),
                b.getTitlesUnchanged(), b.getTitlesSkipped(), b.getTitlesSuspended(), b.getPrincipalTotal().amount());
        return new ImportBatchView(b.getId(), b.getCreditorId(), b.getPortfolioId(), b.getLayoutId(), b.getLayoutCode(),
                b.getLayoutVersion(), b.getSource().name(), b.getMode().name(), b.getStatus().name(), b.getStatusReason(),
                b.getFileName(), b.getFileSize(), b.getFileSha256(), b.isForced(), b.getForceReason(),
                b.getDuplicateOfBatchId(), b.getReprocessOfBatchId(), counters, b.getIssuesRecorded(),
                b.isIssuesTruncated(), b.getTotalRows(), b.progressPercent(), b.getLastProcessedRow(),
                b.getCancelRequestedAt(), b.getCreatedBy(), b.getCreatedAt(), b.getStartedAt(), b.getFinishedAt());
    }

    static ImportIssueView issue(ImportRowIssue i) {
        return new ImportIssueView(i.getId(), i.getRowNumber(), i.getSeverity().name(), i.getField(), i.getCode(),
                i.getMessage(), i.getValueExcerpt());
    }

    static ImportLayoutView layout(ImportLayout l) {
        var columns = l.getColumns().stream()
                .sorted(Comparator.comparing(LayoutColumn::getField))
                .map(c -> new ColumnCommand(c.getField().column(), c.getHeader(), c.getPosition()))
                .toList();
        return new ImportLayoutView(l.getId(), l.getCreditorId(), l.getPortfolioId(), l.getCode(), l.getLayoutVersion(), l.getName(),
                l.getFormat().name(), l.getCsvDelimiter(), l.getCsvEncoding(), l.isHasHeader(), l.getSheetName(),
                columns, l.isActive(), l.getCreatedAt());
    }
}
