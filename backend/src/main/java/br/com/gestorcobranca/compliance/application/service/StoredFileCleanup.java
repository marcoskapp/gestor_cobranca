package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O armazenamento de arquivos não participa da transação do banco: um arquivo gravado por um
 * caso de uso cuja transação é desfeita ficaria órfão (dado pessoal sem registro que o
 * referencie). Este componente remove o arquivo se a transação corrente for desfeita.
 */
@Component
class StoredFileCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(StoredFileCleanup.class);

    private final FileStorage fileStorage;

    StoredFileCleanup(FileStorage fileStorage) {
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
                                .log("Arquivo gravado por transação desfeita não pôde ser removido");
                    }
                }
            }
        });
    }
}
