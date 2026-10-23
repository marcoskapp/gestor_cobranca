/**
 * Ingestão de carteiras (M02/F04, RB01, 5.2): layouts de importação por credor, lotes de carga
 * por arquivo (CSV/XLSX) ou API, validação linha a linha, relatório de erros mascarado e
 * processamento assíncrono, em blocos, retomável e idempotente. A gravação de devedores e títulos
 * é delegada ao módulo debt ({@code DebtIngestionPort}); este módulo guarda a origem e o histórico
 * de cada carga.
 */
@ApplicationModule(displayName = "Ingestion",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases"})
package br.com.gestorcobranca.ingestion;

import org.springframework.modulith.ApplicationModule;
