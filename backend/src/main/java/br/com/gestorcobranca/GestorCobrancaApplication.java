package br.com.gestorcobranca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

/**
 * Ponto de entrada do monólito modular. Cada pacote de primeiro nível abaixo deste
 * é um módulo (bounded context) verificado pelo Spring Modulith.
 * <p>
 * Com o argumento {@code --app.mode=migrate}, apenas aplica as migrations e encerra
 * (ver {@link MigrationRunner}).
 */
@Modulithic(systemName = "Gestor de Cobrança", sharedModules = "shared")
@SpringBootApplication
@ConfigurationPropertiesScan
public class GestorCobrancaApplication {

    public static void main(String[] args) {
        if (MigrationRunner.isRequested(args)) {
            System.exit(MigrationRunner.run(args));
        }
        SpringApplication.run(GestorCobrancaApplication.class, args);
    }
}
