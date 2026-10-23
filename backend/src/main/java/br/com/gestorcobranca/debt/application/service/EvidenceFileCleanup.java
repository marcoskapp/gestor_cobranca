package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O armazenamento de arquivos não participa da transação do banco: a evidência gravada por um
 * registro cuja transação é desfeita ficaria órfã. Remove o arquivo se a transação corrente for
 * desfeita.
 */
@Component
class EvidenceFileCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(EvidenceFileCleanup.class);

    private final FileStorage fileStorage;

    EvidenceFileCleanup(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
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
