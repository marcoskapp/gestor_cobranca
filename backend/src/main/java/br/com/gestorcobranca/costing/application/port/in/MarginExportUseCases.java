package br.com.gestorcobranca.costing.application.port.in;

import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginFilter;

import java.io.InputStream;

/**
 * Exportação do relatório de margem ({@code GET /api/v1/costing/margin/export}): XLSX com as abas resumo, por credor,
 * por carteira, por mês e premissas; CSV no formato longo. Exige {@code COST_VIEW} e {@code REPORT_EXPORT}, aplica o
 * mesmo escopo do painel e registra {@code DATA_EXPORTED} no log de acesso.
 */
public interface MarginExportUseCases {

    ExportFile export(MarginFilter filter, String format);

    record ExportFile(String fileName, String contentType, long size, String sha256, InputStream content) {
    }
}
