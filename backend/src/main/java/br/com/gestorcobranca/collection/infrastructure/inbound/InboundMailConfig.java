package br.com.gestorcobranca.collection.infrastructure.inbound;

import br.com.gestorcobranca.collection.application.InboundMailProperties;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Adaptadores de recebimento das respostas por e-mail, conforme {@code app.collection.inbound-mail.mode}:
 * {@code ses} (fila SQS + bucket de entrada; credenciais do papel da instância) ou {@code local} (pasta de .eml, nunca
 * no perfil prod). {@code disabled} (padrão) não cria nada.
 */
@Configuration
class InboundMailConfig {

    @Configuration
    @ConditionalOnProperty(prefix = "app.collection.inbound-mail", name = "mode", havingValue = "ses")
    static class Ses {

        /**
         * Clientes AWS próprios (não expostos como beans, para não concorrer com o {@code S3Client} do armazenamento);
         * fechados com o consumidor.
         */
        @Bean(destroyMethod = "close")
        SesInboundMailConsumer sesInboundMailConsumer(InboundMailUseCases inbound, InboundMailProperties properties,
                                                      JsonCodec json, MeterRegistry registry) {
            var sqs = SqsClient.builder();
            var s3 = S3Client.builder();
            if (properties.ses().region() != null) {
                sqs.region(Region.of(properties.ses().region()));
                s3.region(Region.of(properties.ses().region()));
            }
            return new SesInboundMailConsumer(sqs.build(), s3.build(), inbound, properties, json, registry);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "app.collection.inbound-mail", name = "mode", havingValue = "local")
    static class Local {

        @Bean
        LocalDirectoryInboundMailSource localDirectoryInboundMailSource(InboundMailUseCases inbound,
                                                                        InboundMailProperties properties,
                                                                        Environment environment) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("app.collection.inbound-mail.mode=local não é permitido no perfil prod");
            }
            return new LocalDirectoryInboundMailSource(inbound, properties);
        }
    }
}
