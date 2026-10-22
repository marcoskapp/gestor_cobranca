package br.com.gestorcobranca.shared.storage;

import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Seleciona a implementação de {@link FileStorage}. Em EC2, as credenciais AWS vêm do perfil de
 * instância (IAM role) pela cadeia padrão do SDK — nenhuma chave fica em configuração.
 */
@Configuration
class StorageConfig {

    @Configuration
    @ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "s3", matchIfMissing = true)
    static class S3 {

        @Bean(destroyMethod = "close")
        S3Client s3Client(StorageProperties properties) {
            return S3Client.builder().region(Region.of(properties.region())).build();
        }

        @Bean(destroyMethod = "close")
        S3Presigner s3Presigner(StorageProperties properties) {
            return S3Presigner.builder().region(Region.of(properties.region())).build();
        }

        @Bean
        FileStorage fileStorage(S3Client client, S3Presigner presigner, StorageProperties properties,
                                CurrentActor currentActor, Clock clock, MeterRegistry meterRegistry) {
            return new S3FileStorage(client, presigner, properties, currentActor, clock, meterRegistry);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "app.storage", name = "provider", havingValue = "filesystem")
    static class FileSystem {

        @Bean
        FileStorage fileStorage(StorageProperties properties, CurrentActor currentActor, Clock clock) {
            Path root = properties.localRoot() == null ? Path.of("./var/storage") : properties.localRoot();
            return new FileSystemFileStorage(root, currentActor, clock);
        }
    }
}
