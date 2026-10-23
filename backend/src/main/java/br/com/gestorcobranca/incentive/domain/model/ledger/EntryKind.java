package br.com.gestorcobranca.incentive.domain.model.ledger;

/** Natureza do lançamento: crédito por pagamento confirmado ou compensação por estorno. */
public enum EntryKind {
    ACCRUAL, REVERSAL
}
