package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * O armazenamento de arquivos não participa da transação do banco: a evidência gravada por um
 * registro cuja transação é desfeita ficaria órfã. Remove o arquivo se a transação corrente for
 * desfeita ou, quando o arquivo foi gravado sem transação aberta, logo que o registro é desfeito.
 */
@Component
class EvidenceFileCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceFileCleanup.class);

    /** Desfecho ainda não informado: a transação de registro nem chegou a começar (ou falhou antes do callback). */
    static final int NOT_STARTED = -1;

    private final FileStorage fileStorage;

    EvidenceFileCleanup(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * Registra, numa transação própria, o que referencia um arquivo já gravado sem transação aberta. Se a transação não
     * começou ou foi desfeita, o arquivo é removido. Se o desfecho é incerto (o commit pode ter acontecido, ex.: queda da
     * conexão durante o commit), o arquivo é <b>mantido</b> — apagar deixaria um registro confirmado sem a evidência —
     * e fica um log técnico com a chave (sem dado pessoal). A exceção original sempre segue para quem chamou.
     */
    <T> T registerOrDiscard(String key, TransactionOperations transaction, Supplier<T> registration) {
        AtomicInteger outcome = new AtomicInteger(NOT_STARTED);
        try {
            return transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completion) {
                        outcome.set(completion);
                    }
                });
                return registration.get();
            });
        } catch (RuntimeException e) {
            int completion = outcome.get();
            if (completion == NOT_STARTED || completion == TransactionSynchronization.STATUS_ROLLED_BACK) {
                discard(key);
            } else {
                LOG.atWarn().addKeyValue("storageKey", key).addKeyValue("transactionStatus", completion)
                        .log("Evidência mantida: o registro falhou com desfecho incerto da transação");
            }
            throw e;
        }
    }

    /**
     * Remove já um arquivo gravado sem transação aberta cujo registro não chegou ao banco (pedido de correção de valor
     * recusado ou com falha no registro).
     */
    void discard(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            LOG.atError().addKeyValue("storageKey", key).setCause(e)
                    .log("Evidência sem registro no banco não pôde ser removida");
        }
    }

    void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        fileStorage.delete(key);
                    } catch (RuntimeException e) {
                        LOG.atError().addKeyValue("storageKey", key).setCause(e)
                                .log("Evidência gravada por transação desfeita não pôde ser removida");
                    }
                }
            }
        });
    }
}
