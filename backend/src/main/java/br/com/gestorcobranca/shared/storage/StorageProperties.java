package br.com.gestorcobranca.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * @param provider   {@code s3} (padrão, produção) ou {@code filesystem} (desenvolvimento local)
 * @param bucket     bucket S3
 * @param region     região AWS
 * @param kmsKeyId   opcional: chave KMS para SSE-KMS; sem ela usa SSE-S3 (AES256)
 * @param localRoot  diretório raiz do provider filesystem
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String provider, String bucket, String region, String kmsKeyId, Path localRoot) {

    public StorageProperties {
        provider = provider == null ? "s3" : provider;
    }
}
