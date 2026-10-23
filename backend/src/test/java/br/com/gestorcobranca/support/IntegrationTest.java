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
 *   <li><b>Servidor externo</b>: se {@code IT_DB_ADMIN_URL} estiver definido (ex.: serviço
 *       PostgreSQL do CI). Requer os papéis já criados com {@code scripts/db/bootstrap-roles.sql}.</li>
 * </ul>
 * Em ambos, cada contexto Spring de teste usa um banco novo e isolado, com prefixo
 * {@code IT_DB_NAME_PREFIX} (padrão {@code cobranca_it_}).
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    private static final String OWNER_PASSWORD = System.getenv().getOrDefault("IT_DB_OWNER_PASSWORD", "app_owner_it");
    private static final String RUNTIME_PASSWORD = System.getenv().getOrDefault("IT_DB_RUNTIME_PASSWORD", "app_runtime_it");

    /** Servidor PostgreSQL da execução (externo ou contêiner), iniciado uma única vez. */
    private static volatile Server server;
    /** Banco avulso para testes que não sobem contexto Spring (ex.: {@code MigrationRunnerIT}). */
    private static volatile String standaloneUrl;

    private record Server(String adminUrl, String user, String password) {
    }

    protected static synchronized String jdbcUrl() {
        if (standaloneUrl == null) {
            standaloneUrl = createIsolatedDatabase();
        }
        return standaloneUrl;
    }

    protected static String ownerPassword() {
        return OWNER_PASSWORD;
    }

    /**
     * Cada contexto Spring de teste recebe um banco próprio. Contextos distintos (por exemplo, com
     * {@code @Import} de um relógio de teste) ficam vivos ao mesmo tempo no cache do Spring, cada um
     * com seus workers de jobs e seu registro de publicações de eventos; num banco compartilhado, um
     * contexto reivindicaria jobs e publicações de outro.
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = createIsolatedDatabase();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "app_runtime_login");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "app_owner");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
    }

    private static synchronized Server server() {
        if (server == null) {
            String adminUrl = System.getenv("IT_DB_ADMIN_URL");
            if (adminUrl != null && !adminUrl.isBlank()) {
                server = new Server(adminUrl, System.getenv("IT_DB_ADMIN_USER"), System.getenv("IT_DB_ADMIN_PASSWORD"));
            } else {
                PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine")
                        .withDatabaseName("cobranca")
                        .withCopyFileToContainer(MountableFile.forClasspathResource("it/bootstrap-roles.sql"),
                                "/docker-entrypoint-initdb.d/10-roles.sql");
                container.start();
                server = new Server(container.getJdbcUrl(), container.getUsername(), container.getPassword());
            }
        }
        return server;
    }

    private static String createIsolatedDatabase() {
        Server srv = server();
        String adminUrl = srv.adminUrl();
        // IT_DB_NAME_PREFIX identifica os bancos de uma execução, para que a limpeza de uma execução
        // não remova bancos de outra rodando em paralelo no mesmo servidor
        String prefix = System.getenv().getOrDefault("IT_DB_NAME_PREFIX", "cobranca_it_");
        if (!prefix.matches("[a-z][a-z0-9_]{0,40}")) {
            throw new IllegalStateException("IT_DB_NAME_PREFIX inválido: use letras minúsculas, dígitos e _");
        }
        String database = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(adminUrl, srv.user(), srv.password());
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + database + " owner app_owner");
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível criar o banco de teste em " + adminUrl, e);
        }
        return adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;
    }
}
