package br.com.gestorcobranca.negotiation.application.port.out;

import br.com.gestorcobranca.negotiation.domain.model.calculation.IndexSeries;

import java.util.Optional;

/**
 * Fonte das variações mensais dos índices de correção monetária do tenant corrente. A
 * implementação atual é a tabela manual mantida pela assessoria
 * ({@code ManualMonetaryIndexProvider}); uma integração com fonte oficial (BCB SGS/IBGE) pode
 * substituí-la sem mudar o cálculo.
 */
public interface MonetaryIndexProvider {

    /** Série completa do índice (vazio se o código não existir no tenant). */
    Optional<IndexSeries> series(String indexCode);
}
