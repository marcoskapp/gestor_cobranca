package br.com.gestorcobranca.ingestion.application.port.out;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Leitura em streaming das ocorrências de um lote, ordenadas por linha, para o relatório de
 * erros — sem materializar todas em memória. Deve ser chamada dentro de transação.
 */
public interface ImportIssueExport {

    void forEachIssue(UUID batchId, Consumer<IssueLine> consumer);
}
