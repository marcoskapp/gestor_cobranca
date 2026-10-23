package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Remove do armazenamento o arquivo gravado por uma transação que foi desfeita (exportação, comprovante). */
@Component
class FileCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(FileCleanup.class);

    private final FileStorage fileStorage;

    FileCleanup(FileStorage fileStorage) {
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
