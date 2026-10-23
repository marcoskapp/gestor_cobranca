package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralView;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Situação da garantia durante a execução, pela porta pública do debt
 * ({@link CollateralUseCases#changeStatus}, que valida as transições e audita). Mudança para a
 * situação atual não é repetida.
 */
@Component
class CollateralStatusUpdater {

    private final CollateralUseCases collaterals;

    CollateralStatusUpdater(CollateralUseCases collaterals) {
        this.collaterals = collaterals;
    }

    CollateralView get(UUID collateralId) {
        return collaterals.get(collateralId);
    }

    /** @return true se a situação mudou */
    boolean change(UUID collateralId, String status, String reason) {
        CollateralView current = collaterals.get(collateralId);
        if (current.status().equals(status)) {
            return false;
        }
        collaterals.changeStatus(collateralId, status, reason);
        return true;
    }

    /** Mudança somente se a garantia estiver na situação esperada (efeitos automáticos). */
    boolean changeIf(UUID collateralId, String expected, String status, String reason) {
        CollateralView current = collaterals.get(collateralId);
        if (!current.status().equals(expected)) {
            return false;
        }
        collaterals.changeStatus(collateralId, status, reason);
        return true;
    }
}
