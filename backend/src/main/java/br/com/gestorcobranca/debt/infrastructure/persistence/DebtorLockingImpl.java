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
}
