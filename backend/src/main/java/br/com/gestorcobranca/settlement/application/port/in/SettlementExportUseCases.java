package br.com.gestorcobranca.settlement.application.port.in;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Exportação do demonstrativo aprovado para o credor: CSV e XLSX conciliáveis (registros com chaves e
 * totais de controle) e PDF resumo, guardados no {@code FileStorage} com SHA-256. Gerar:
 * {@code SETTLEMENT_CLOSE}; consultar e baixar: {@code SETTLEMENT_VIEW} (download registra
 * {@code FILE_DOWNLOADED}).
 */
public interface SettlementExportUseCases {

    /** @param format CSV, XLSX ou PDF */
    ExportView export(UUID statementId, String format);

    List<ExportView> list(UUID statementId);

    ExportFile download(UUID exportId);

    record ExportView(UUID id, UUID statementId, String format, String fileName, long fileSize, String sha256,
                      int rows, UUID generatedBy, Instant createdAt) {
    }

    record ExportFile(String fileName, String contentType, long size, String sha256, InputStream content) {
    }
}
