package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.Map;

/**
 * Bloco de um devedor no documento (cabeçalho seguido da tabela de títulos).
 *
 * @param index  posição do bloco (0..n-1)
 * @param fields destino → valor lido no cabeçalho (ex.: {@code nome}, {@code documento}, {@code info:codigo})
 * @param region linha que abre o bloco (nula quando o documento não tem cabeçalho de devedor)
 */
public record DebtorBlock(int index, Map<String, CellValue> fields, SourceRef region) {

    public DebtorBlock {
        fields = Map.copyOf(fields);
    }
}
