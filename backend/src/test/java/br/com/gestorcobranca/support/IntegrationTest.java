package br.com.gestorcobranca.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Base dos testes de integração: Spring completo contra PostgreSQL real, com os mesmos papéis
 * de produção (migrations como {@code app_owner}, aplicação como {@code app_runtime_login}),
 * para que RLS e privilégios sejam de fato exercitados.
 * <p>
 * Dois modos:
 * <ul>
 *   <li><b>Testcontainers</b> (padrão): sobe {@code postgres:16-alpine} e executa
 *       {@code it/bootstrap-roles.sql}.</li>
 *   <li><b>Banco externo</b>: se {@code IT_DB_ADMIN_URL} estiver definido (ex.: serviço
 *       PostgreSQL do CI), cria um banco novo e isolado por execução nesse servidor. Requer os
 *       papéis já criados com {@code scripts/db/bootstrap-roles.sql}.</li>
 * </ul>
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    private static final String OWNER_PASSWORD = System.getenv().getOrDefault("IT_DB_OWNER_PASSWORD", "app_owner_it");
    private static final String RUNTIME_PASSWORD = System.getenv().getOrDefault("IT_DB_RUNTIME_PASSWORD", "app_runtime_it");
    private static final String JDBC_URL = startDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "app_runtime_login");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", () -> JDBC_URL);
        registry.add("spring.flyway.user", () -> "app_owner");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
    }

    private static String startDatabase() {
        String adminUrl = System.getenv("IT_DB_ADMIN_URL");
        if (adminUrl != null && !adminUrl.isBlank()) {
            return createIsolatedDatabase(adminUrl, System.getenv("IT_DB_ADMIN_USER"), System.getenv("IT_DB_ADMIN_PASSWORD"));
        }
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("cobranca")
                .withCopyFileToContainer(MountableFile.forClasspathResource("it/bootstrap-roles.sql"),
                        "/docker-entrypoint-initdb.d/10-roles.sql");
        container.start();
        return container.getJdbcUrl();
    }

    private static String createIsolatedDatabase(String adminUrl, String user, String password) {
        String database = "cobranca_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + database + " owner app_owner");
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível criar o banco de teste em " + adminUrl, e);
        }
        return adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;
    }
}
