package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

/**
 * Problema do arquivo como um todo (cabeçalho, estrutura, codificação): impede a leitura das
 * linhas e falha o lote. Problemas de uma linha não usam esta exceção — viram {@link RowIssue}.
 */
public class ImportFileException extends BusinessRuleException {

    public ImportFileException(String code, String message) {
        super(code, message);
    }
}
