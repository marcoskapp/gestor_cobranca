package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Evidência gravada sem transação aberta (decisão do responsável sobre a 872): só é removida quando o registro não
 * chegou ao banco — transação desfeita ou que nem começou; com desfecho incerto (o commit pode ter acontecido), fica.
 */
class EvidenceFileCleanupTest {

    private static final String KEY = "evidence/2026/11/abc";

    private final FileStorage storage = mock(FileStorage.class);
    private final EvidenceFileCleanup cleanup = new EvidenceFileCleanup(storage);

    /** Transação de teste: executa o callback e informa às sincronizações o desfecho escolhido. */
    private static TransactionOperations completing(int status, RuntimeException commitFailure) {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                TransactionSynchronizationManager.initSynchronization();
                RuntimeException failure = null;
                T result = null;
                try {
                    result = action.doInTransaction(new SimpleTransactionStatus());
                } catch (RuntimeException e) {
                    failure = e;
                }
                List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
                TransactionSynchronizationManager.clearSynchronization();
                synchronizations.forEach(s -> s.afterCompletion(status));
                if (failure != null) {
                    throw failure;
                }
                if (commitFailure != null) {
                    throw commitFailure;
                }
                return result;
            }
        };
    }

    @Test
    void ruleRefusalRolledBackRemovesTheFile() {
        assertThatThrownBy(() -> cleanup.registerOrDiscard(KEY,
                completing(TransactionSynchronization.STATUS_ROLLED_BACK, null), () -> {
                    throw new BusinessRuleException("title_amount.would_settle", "zeraria o saldo");
                })).isInstanceOf(BusinessRuleException.class);
        verify(storage).delete(KEY);
    }

    @Test
    void transactionThatNeverStartedRemovesTheFile() {
        TransactionOperations unavailable = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                throw new CannotCreateTransactionException("sem conexão");
            }
        };
        assertThatThrownBy(() -> cleanup.registerOrDiscard(KEY, unavailable, () -> "ok"))
                .isInstanceOf(CannotCreateTransactionException.class);
        verify(storage).delete(KEY);
    }

    @Test
    void uncertainCommitKeepsTheFile() {
        assertThatThrownBy(() -> cleanup.registerOrDiscard(KEY, completing(TransactionSynchronization.STATUS_UNKNOWN,
                new TransactionSystemException("conexão caiu no commit")), () -> "ok"))
                .isInstanceOf(TransactionSystemException.class);
        verify(storage, never()).delete(KEY);
    }

    @Test
    void committedRegistrationKeepsTheFile() {
        assertThat(cleanup.registerOrDiscard(KEY, completing(TransactionSynchronization.STATUS_COMMITTED, null),
                () -> "registrado")).isEqualTo("registrado");
        verify(storage, never()).delete(KEY);
    }
}
