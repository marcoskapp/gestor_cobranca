package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Evidência de pagamento externo (comprovante): PDF, PNG ou JPEG, tipo conferido pelo conteúdo (não só
 * pelo cabeçalho declarado), tamanho limitado, gravada no {@code FileStorage} ({@code EVIDENCE}) com
 * SHA-256 e removida se a transação for desfeita.
 */
@Component
class EvidenceStore {

    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", new byte[]{'%', 'P', 'D', 'F', '-'},
            "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    private final FileStorage fileStorage;
    private final FileCleanup cleanup;
    private final PaymentProperties properties;

    EvidenceStore(FileStorage fileStorage, FileCleanup cleanup, PaymentProperties properties) {
        this.fileStorage = fileStorage;
        this.cleanup = cleanup;
        this.properties = properties;
    }

    Payment.Evidence store(EvidenceUpload upload) {
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "payment.evidence_required",
                "Evidência do pagamento obrigatória");
        Guard.require(upload.size() <= properties.evidence().maxSizeBytes(), "payment.evidence_too_large",
                "Evidência excede o tamanho máximo");
        String contentType = upload.contentType() == null ? ""
                : upload.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        byte[] signature = SIGNATURES.get(contentType);
        Guard.require(signature != null, "payment.evidence_type_invalid", "Evidência deve ser PDF, PNG ou JPEG");
        BufferedInputStream in = new BufferedInputStream(upload.content());
        try {
            in.mark(16);
            byte[] head = in.readNBytes(signature.length);
            in.reset();
            if (!Arrays.equals(head, signature)) {
                throw new BusinessRuleException("payment.evidence_type_mismatch",
                        "O conteúdo da evidência não corresponde ao tipo informado");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler a evidência enviada", e);
        }
        StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, upload.fileName(), contentType, limited(in),
                upload.size());
        cleanup.deleteOnRollback(stored.key());
        return new Payment.Evidence(stored.key(), stored.originalName(), contentType, stored.size(), stored.sha256());
    }

    private InputStream limited(InputStream in) {
        long max = properties.evidence().maxSizeBytes();
        return new InputStream() {
            private long read;

            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b >= 0 && ++read > max) {
                    throw new BusinessRuleException("payment.evidence_too_large", "Evidência excede o tamanho máximo");
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = in.read(buffer, offset, length);
                if (n > 0 && (read += n) > max) {
                    throw new BusinessRuleException("payment.evidence_too_large", "Evidência excede o tamanho máximo");
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    }
}
