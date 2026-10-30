package br.com.gestorcobranca.ingestion.application.port.out;

import java.util.List;
import java.util.Map;

/** Arquivo do layout canônico ({@code PADRAO}, CSV UTF-8 com {@code ;}) gerado a partir dos títulos aprovados do PDF. */
public interface CanonicalFileWriter {

    /**
     * @param columns colunas do layout canônico, na ordem do cabeçalho
     * @param rows    valores por coluna (ausente = vazio)
     */
    byte[] csv(List<String> columns, List<Map<String, String>> rows);
}
