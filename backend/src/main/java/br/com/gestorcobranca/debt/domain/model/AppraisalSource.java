package br.com.gestorcobranca.debt.domain.model;

/** Origem da avaliação de uma garantia. */
public enum AppraisalSource {
    /** Informada pelo credor (carga ou cadastro). */
    CREDITOR,
    /** Laudo de avaliador. */
    APPRAISER,
    /** Tabela FIPE (veículos). */
    FIPE,
    /** Pesquisa de mercado. */
    MARKET,
    /** Valor de lance/arrematação em leilão. */
    AUCTION
}
