package br.com.gestorcobranca.incentive.application.port.in;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Exportação dos extratos aprovados para a folha (requer {@code COMMISSION_STATEMENT_APPROVE}). */
public interface StatementExportUseCases {

    /**
     * Gera o arquivo com todos os extratos aprovados (e já exportados) do período, marca os
     * aprovados como {@code EXPORTED} e guarda o arquivo (evidência com hash).
     *
     * @param format CSV ou XLSX
     */
    ExportFile export(YearMonth period, String format);

    List<ExportView> list(YearMonth period);

    ExportFile download(UUID exportId);

    /** Arquivo para download; o chamador fecha o stream. */
    record ExportFile(String fileName, String contentType, long size, InputStream content) {
    }

    record ExportView(UUID id, YearMonth period, String format, String fileName, long size, String sha256,
                      int statements, BigDecimal totalAmount, UUID exportedBy, Instant exportedAt) {
    }
}
