package br.com.gestorcobranca.shared.storage;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.CurrentActor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Implementação em disco para desenvolvimento local e testes ({@code app.storage.provider=filesystem}).
 * Não deve ser usada em produção: não oferece URL temporária assinada nem criptografia.
 */
public class FileSystemFileStorage implements FileStorage {

    private final Path root;
    private final CurrentActor currentActor;
    private final Clock clock;

    public FileSystemFileStorage(Path root, CurrentActor currentActor, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    public StoredFile store(FileCategory category, String originalName, String contentType, InputStream content, long size) {
        UUID tenantId = currentActor.tenantId();
        UUID fileId = Ids.newId();
        String key = StorageKeys.build(tenantId, category, fileId, originalName, LocalDate.now(clock));
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            HashingCopy.Result copy = HashingCopy.copy(content, target);
            return new StoredFile(fileId, key, StorageKeys.sanitize(originalName), contentType, copy.size(), copy.sha256());
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao armazenar arquivo", e);
        }
    }

    @Override
    public InputStream open(String key) {
        StorageKeys.requireTenant(key, currentActor.tenantId());
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Arquivo não encontrado", e);
        }
    }

    @Override
    public String temporaryDownloadUrl(String key, Duration validity) {
        throw new UnsupportedOperationException("URL temporária disponível apenas no provider S3; use o download via API");
    }

    @Override
    public void delete(String key) {
        StorageKeys.requireTenant(key, currentActor.tenantId());
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao remover arquivo", e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new SecurityException("Caminho inválido");
        }
        return path;
    }
}
