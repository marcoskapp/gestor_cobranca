package br.com.gestorcobranca.shared.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * Armazenamento de arquivos (S3 em produção). Todas as chaves são prefixadas pelo tenant
 * ({@code tenants/<tenantId>/<categoria>/aaaa/mm/<uuid>-<nome>}), e o tenant é sempre o do ator
 * corrente: não é possível ler arquivo de outro tenant por esta interface.
 */
public interface FileStorage {

    /**
     * Grava o conteúdo e calcula SHA-256 (integridade e evidência).
     *
     * @param size tamanho em bytes, ou -1 se desconhecido
     */
    StoredFile store(FileCategory category, String originalName, String contentType, InputStream content, long size);

    /** Abre o arquivo para leitura. O chamador deve fechar o stream. */
    InputStream open(String key);

    /** URL temporária de download (padrão: 5 min). O chamador registra o acesso. */
    String temporaryDownloadUrl(String key, Duration validity);

    /** Remoção física — usada apenas pelo processo de retenção e descarte (LGPD). */
    void delete(String key);

    record StoredFile(UUID id, String key, String originalName, String contentType, long size, String sha256) {
    }
}
