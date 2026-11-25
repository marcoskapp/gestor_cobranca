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

    /**
     * Descarte: o arquivo deixa de ser legível pela aplicação. Usado pela retenção e pela anonimização (LGPD) e para
     * desfazer upload de transação desfeita ou arquivo obsoleto. No S3 versionado, a versão anterior fica recuperável
     * pela operação por até 30 dias (1 dia para importação) e então expira (decisões 878 e 1462); por isso as chaves
     * nunca são reaproveitadas.
     */
    void delete(String key);

    record StoredFile(UUID id, String key, String originalName, String contentType, long size, String sha256) {
    }
}
