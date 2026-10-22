package br.com.gestorcobranca.shared.storage;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Implementação S3. O conteúdo é primeiro gravado em arquivo temporário enquanto o SHA-256 é
 * calculado — garantindo tamanho conhecido, hash íntegro e reenvio seguro — e então enviado
 * com criptografia no servidor (SSE-KMS se configurado, senão SSE-S3).
 */
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;
    private final CurrentActor currentActor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public S3FileStorage(S3Client s3, S3Presigner presigner, StorageProperties properties, CurrentActor currentActor,
                         Clock clock, MeterRegistry meterRegistry) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
        this.currentActor = currentActor;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public StoredFile store(FileCategory category, String originalName, String contentType, InputStream content, long size) {
        UUID tenantId = currentActor.tenantId();
        UUID fileId = Ids.newId();
        String key = StorageKeys.build(tenantId, category, fileId, originalName, LocalDate.now(clock));
        Path temp = null;
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            temp = Files.createTempFile("upload-", ".bin");
            HashingCopy.Result copy = HashingCopy.copy(content, temp);
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .contentLength(copy.size())
                    .metadata(Map.of("sha256", copy.sha256(), "tenant-id", tenantId.toString(), "category", category.name()));
            if (category == FileCategory.EVIDENCE) {
                // regra de lifecycle do bucket move evidências antigas para o Glacier (infra/terraform, módulo storage)
                request.tagging("retention-class=evidence");
            }
            if (properties.kmsKeyId() != null && !properties.kmsKeyId().isBlank()) {
                request.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(properties.kmsKeyId());
            } else {
                request.serverSideEncryption(ServerSideEncryption.AES256);
            }
            s3.putObject(request.build(), RequestBody.fromFile(temp));
            return new StoredFile(fileId, key, StorageKeys.sanitize(originalName), contentType, copy.size(), copy.sha256());
        } catch (IOException e) {
            outcome = "error";
            throw new UncheckedIOException("Falha ao armazenar arquivo", e);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("storage.operation", "operation", "store", "category", category.name(), "outcome", outcome));
            deleteQuietly(temp);
        }
    }

    @Override
    public InputStream open(String key) {
        StorageKeys.requireTenant(key, currentActor.tenantId());
        return s3.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }

    @Override
    public String temporaryDownloadUrl(String key, Duration validity) {
        StorageKeys.requireTenant(key, currentActor.tenantId());
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(validity == null ? Duration.ofMinutes(5) : validity)
                .getObjectRequest(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

    @Override
    public void delete(String key) {
        StorageKeys.requireTenant(key, currentActor.tenantId());
        s3.deleteObject(b -> b.bucket(properties.bucket()).key(key));
    }

    private static void deleteQuietly(Path temp) {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // arquivo temporário: o diretório tmp do sistema é limpo periodicamente
            }
        }
    }
}
