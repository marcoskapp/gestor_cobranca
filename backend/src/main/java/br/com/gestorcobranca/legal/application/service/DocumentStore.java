package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.LegalProperties;
import br.com.gestorcobranca.legal.domain.service.FileSignature;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;

/**
 * Gravação de documentos do jurídico no {@link FileStorage} do tenant (categoria EVIDENCE): tipo
 * conferido pelo conteúdo (PDF, PNG, JPEG), tamanho máximo verificado durante a cópia e remoção do
 * arquivo se a transação do registro for desfeita (o armazenamento não participa da transação).
 */
@Component
class DocumentStore {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentStore.class);

    private final FileStorage fileStorage;
    private final LegalProperties properties;

    DocumentStore(FileStorage fileStorage, LegalProperties properties) {
        this.fileStorage = fileStorage;
        this.properties = properties;
    }

    StoredFile store(String fileName, InputStream content, long declaredSize) {
        long max = properties.documents().maxSizeBytes();
        Guard.require(content != null && declaredSize != 0, "legal_document.empty", "Arquivo vazio");
        Guard.require(declaredSize <= max, "legal_document.too_large", "Arquivo excede o tamanho máximo");
        try {
            PushbackInputStream in = new PushbackInputStream(content, FileSignature.HEADER_BYTES);
            byte[] head = new byte[FileSignature.HEADER_BYTES];
            int read = in.readNBytes(head, 0, head.length);
            Guard.require(read > 0, "legal_document.empty", "Arquivo vazio");
            String contentType = FileSignature.detect(head, read).orElseThrow(() -> new BusinessRuleException(
                    "legal_document.type_invalid", "Documento deve ser PDF, PNG ou JPEG"));
            in.unread(head, 0, read);
            StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, fileName, contentType, new Limited(in, max),
                    declaredSize);
            deleteOnRollback(stored.key());
            return stored;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o documento enviado", e);
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof TooLarge) {
                throw new BusinessRuleException("legal_document.too_large", "Arquivo excede o tamanho máximo");
            }
            throw e;
        }
    }

    InputStream open(String key) {
        return fileStorage.open(key);
    }

    private void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        fileStorage.delete(key);
                    } catch (RuntimeException e) {
                        LOG.atError().addKeyValue("storageKey", key).setCause(e)
                                .log("Documento gravado por transação desfeita não pôde ser removido");
                    }
                }
            }
        });
    }

    private static final class TooLarge extends IOException {
        TooLarge() {
            super("limite de tamanho excedido");
        }
    }

    /** Interrompe a cópia ao passar do limite (tamanho declarado pode ser desconhecido ou falso). */
    private static final class Limited extends FilterInputStream {

        private final long max;
        private long count;

        Limited(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                check(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                check(n);
            }
            return n;
        }

        private void check(int n) throws IOException {
            count += n;
            if (count > max) {
                throw new TooLarge();
            }
        }
    }
}
