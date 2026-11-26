package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.Debtor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

class DebtorLockingImpl implements DebtorLocking {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void refreshForUpdate(Debtor debtor) {
        entityManager.refresh(debtor, LockModeType.PESSIMISTIC_WRITE);
    }

    @Override
    public void refreshForShare(Debtor debtor) {
        // refresh, não consulta: o devedor pode estar no contexto de persistência com o estado de antes da trava; o flush
        // antes guarda o que a transação já mudou nele (a carga atualiza o nome antes de gravar o título)
        entityManager.flush();
        entityManager.refresh(debtor, LockModeType.PESSIMISTIC_READ);
    }
}
