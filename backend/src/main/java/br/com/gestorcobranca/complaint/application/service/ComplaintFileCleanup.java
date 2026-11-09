package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O armazenamento não participa da transação: arquivo gravado por transação desfeita é removido (não fica dado
 * pessoal órfão), e o da quarentena sai depois do commit da liberação ou da recusa.
 */
@Component
class ComplaintFileCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(ComplaintFileCleanup.class);

    private final FileStorage fileStorage;

    ComplaintFileCleanup(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    delete(key, "Arquivo gravado por transação desfeita não pôde ser removido");
                }
            }
        });
    }

    void deleteAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delete(key, "Arquivo da quarentena não pôde ser removido; a manutenção do armazenamento deve removê-lo");
            }
        });
    }

    private void delete(String key, String message) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            LOG.atError().addKeyValue("storageKey", key).setCause(e).log(message);
        }
    }
}
