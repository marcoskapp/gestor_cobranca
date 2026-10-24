package br.com.gestorcobranca.analytics.application.port.in;

import br.com.gestorcobranca.analytics.domain.model.ReportFilter;

import java.io.InputStream;

/**
 * Exportação dos relatórios (CSV ou XLSX) com os mesmos filtros e escopo da tela. Exige
 * {@code REPORT_EXPORT} além da permissão de leitura do relatório; cada exportação gera
 * {@code DATA_EXPORTED} no log de acesso; textos são neutralizados contra fórmulas.
 */
public interface AnalyticsExportUseCases {

    /**
     * @param report  {@code portfolio}, {@code recovery}, {@code conversion}, {@code productivity} ou
     *                {@code reliability}
     * @param format  {@code CSV} ou {@code XLSX}
     */
    ExportFile export(String report, ReportFilter filter, String groupBy, String format);

    record ExportFile(String fileName, String contentType, long size, String sha256, InputStream content) {
    }
}
