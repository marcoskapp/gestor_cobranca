package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportRecordsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.PublicImports;
import br.com.gestorcobranca.publicapi.application.port.out.ApiImportBatchStore;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
class PublicImportService implements PublicImports {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,80}");

    private final ImportBatchUseCases batches;
    private final ApiImportBatchStore origins;
    private final IdempotencyService idempotency;
    private final CurrentActor currentActor;

    PublicImportService(ImportBatchUseCases batches, ApiImportBatchStore origins, IdempotencyService idempotency,
                        CurrentActor currentActor) {
        this.batches = batches;
        this.origins = origins;
        this.idempotency = idempotency;
        this.currentActor = currentActor;
    }

    @Override
    public ImportBatchView importRecords(ImportRecordsCommand command, String idempotencyKey) {
        String key = scopedKey(idempotencyKey);
        ImportBatchView batch = batches.importRecords(command, key);
        origins.register(batch.id(), tokenId(), batch.creditorId());
        return batch;
    }

    @Override
    public ImportBatchView uploadFile(UploadFileCommand command, String idempotencyKey) {
        String key = scopedKey(idempotencyKey);
        String hash = sha256("%s|%s|%s|%s|%s|%s".formatted(command.portfolioId(), command.layoutId(), command.mode(),
                command.force(), command.decimalSeparator(), contentHash(command)));
        UUID batchId = idempotency.execute("publicapi.import_file", key, hash, () -> {
            ImportBatchView created = batches.uploadFile(command);
            origins.register(created.id(), tokenId(), created.creditorId());
            return created.id();
        });
        return batches.get(batchId);
    }

    /** SHA-256 do arquivo lido em streaming (o conteúdo pode ser reaberto: hash aqui e gravação na ingestão). */
    private static String contentHash(UploadFileCommand command) {
        try (java.io.InputStream in = command.content().open()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Falha ao ler o arquivo enviado", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String scopedIdempotencyKey(String idempotencyKey, boolean required) {
        if (!required && (idempotencyKey == null || idempotencyKey.isBlank())) {
            return null;
        }
        return scopedKey(idempotencyKey);
    }

    /** A chave do cliente, isolada pelo token (o escopo de idempotência dos módulos é por assessoria). */
    private String scopedKey(String idempotencyKey) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey.strip()).matches()) {
            throw new BusinessRuleException("public_api.idempotency_key_invalid",
                    "Informe o cabeçalho Idempotency-Key com 8 a 80 caracteres (letras, dígitos, . _ : -)");
        }
        return "tok:" + tokenId() + ":" + idempotencyKey.strip();
    }

    private UUID tokenId() {
        Actor actor = currentActor.get();
        if (actor.type() != Actor.ActorType.INTEGRATION) {
            throw new IllegalStateException("Importação da API pública exige o ator de integração");
        }
        return actor.id();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
