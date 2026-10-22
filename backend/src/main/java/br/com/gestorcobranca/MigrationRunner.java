package br.com.gestorcobranca;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Modo de execução exclusivo de migrations ({@code --app.mode=migrate}).
 * <p>
 * Sobe um contexto Spring mínimo — somente DataSource e Flyway, sem servidor web, jobs,
 * agendamentos ou consumidores de eventos —, aplica as migrations com o papel dono do schema
 * ({@code spring.flyway.user}) e encerra. É executado uma única vez por implantação, antes do
 * rollout das instâncias, que em produção rodam com {@code spring.flyway.enabled=false} e com um
 * papel de banco sem permissão de DDL.
 * <p>
 * Esta classe não tem estereótipo Spring de propósito: não é detectada pelo component scan da
 * aplicação principal.
 */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
final class MigrationRunner {

    static final String MODE_ARGUMENT = "--app.mode=migrate";

    private MigrationRunner() {
    }

    static boolean isRequested(String[] args) {
        for (String arg : args) {
            if (MODE_ARGUMENT.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    static int run(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MigrationRunner.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.enabled=true", "spring.main.banner-mode=off")
                .run(args)) {
            return context.isActive() ? 0 : 1;
        }
    }
}
