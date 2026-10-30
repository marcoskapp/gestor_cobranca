package br.com.gestorcobranca.sandbox.application.port.out;

import java.util.List;

/**
 * Pasta dos PDFs fictícios de demonstração no volume exclusivo do sandbox, com um índice (assessoria, credor, carteira,
 * arquivo, finalidade) lido pelo {@code scripts/sandbox/pdfs.sh}, que copia os arquivos para a máquina.
 */
public interface DemoPdfShelf {

    boolean exists(String tenant, String fileName);

    void store(String tenant, String fileName, byte[] content);

    /** Substitui as entradas da assessoria no índice. */
    void index(String tenant, List<Entry> entries);

    record Entry(String tenant, String creditor, String portfolioCode, String fileName, String purpose) {
    }
}
