package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.Debtor;

/** Fragmento do {@link DebtorJpaRepository}: trava com releitura do estado. */
interface DebtorLocking {

    void refreshForUpdate(Debtor debtor);

    void refreshForShare(Debtor debtor);
}
