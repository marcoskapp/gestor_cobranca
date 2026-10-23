package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.SettlementProperties;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.EvidenceUpload;
import br.com.gestorcobranca.settlement.domain.model.CreditorTransfer;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Comprovante do repasse: PDF, PNG ou JPEG, tipo conferido pelo conteúdo, tamanho limitado, gravado no
 * {@code FileStorage} ({@code EVIDENCE}) com SHA-256 e removido se a transação for desfeita.
 */
@Component
class EvidenceStore {

    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", new byte[]{'%', 'P', 'D', 'F', '-'},
            "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    private final FileStorage fileStorage;
    private final FileCleanup cleanup;
    private final SettlementProperties properties;

    EvidenceStore(FileStorage fileStorage, FileCleanup cleanup, SettlementProperties properties) {
        this.fileStorage = fileStorage;
        this.cleanup = cleanup;
        this.properties = properties;
    }

    /** Lê o comprovante inteiro (limitado ao tamanho máximo) para conferir tipo e calcular a chave de idempotência. */
    byte[] read(EvidenceUpload upload) {
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "transfer.evidence_required",
                "Comprovante do repasse obrigatório");
        Guard.require(upload.size() <= properties.evidenceMaxSizeBytes(), "transfer.evidence_too_large",
                "Comprovante excede o tamanho máximo");
        try (BufferedInputStream in = new BufferedInputStream(upload.content())) {
            byte[] content = in.readNBytes((int) properties.evidenceMaxSizeBytes() + 1);
            Guard.require(content.length > 0 && content.length <= properties.evidenceMaxSizeBytes(),
                    "transfer.evidence_too_large", "Comprovante excede o tamanho máximo");
            return content;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o comprovante enviado", e);
        }
    }

    CreditorTransfer.Evidence store(String fileName, String declaredType, byte[] content) {
        String contentType = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        byte[] signature = SIGNATURES.get(contentType);
        Guard.require(signature != null, "transfer.evidence_type_invalid", "Comprovante deve ser PDF, PNG ou JPEG");
        if (content.length < signature.length
                || !Arrays.equals(Arrays.copyOf(content, signature.length), signature)) {
            throw new BusinessRuleException("transfer.evidence_type_mismatch",
                    "O conteúdo do comprovante não corresponde ao tipo informado");
        }
        String name = fileName == null || fileName.isBlank() ? "comprovante" : fileName.trim();
        name = name.length() <= 200 ? name : name.substring(name.length() - 200);
        StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, name, contentType, new ByteArrayInputStream(content),
                content.length);
        cleanup.deleteOnRollback(stored.key());
        return new CreditorTransfer.Evidence(stored.key(), name, contentType, stored.size(), stored.sha256());
    }
}
