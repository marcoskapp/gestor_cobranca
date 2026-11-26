package br.com.gestorcobranca.sandbox.infrastructure.file;

import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorMfaVault;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorPortalClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cofre do autenticador das pessoas do credor de demonstração (RS-82): só existe com o perfil {@code sandbox} e a carga
 * ligada, a aplicação não sobe com {@code sandbox} + {@code prod}, o arquivo fica com permissão 600 e nenhum
 * {@code toString} expõe o segredo.
 */
class SandboxCreditorMfaVaultTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    @TempDir
    Path dir;

    @Configuration
    @EnableConfigurationProperties(SandboxProperties.class)
    @Import(FileCreditorMfaVault.class)
    static class Config {
    }

    private ApplicationContextRunner runner() {
        // a guarda da carga (pacote config, visibilidade de pacote) entra pelo nome, com as condições avaliadas
        return new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withInitializer(ctx -> {
                    try {
                        ((org.springframework.context.annotation.AnnotationConfigApplicationContext) ctx).register(
                                Class.forName("br.com.gestorcobranca.sandbox.infrastructure.config.SandboxSeedGuard"));
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withPropertyValues("app.sandbox.seed.creditor-mfa-file=" + dir.resolve("creditor-mfa.txt"),
                        "app.environment=sandbox");
    }

    @Test
    void semPerfilSandboxNaoHaCofre() {
        runner().withPropertyValues("app.sandbox.seed.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(CreditorMfaVault.class));
        runner().withPropertyValues("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(CreditorMfaVault.class));
    }

    @Test
    void comPerfilSandboxMasCargaDesligadaNaoHaCofre() {
        runner().withPropertyValues("spring.profiles.active=sandbox", "app.sandbox.seed.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(CreditorMfaVault.class));
    }

    @Test
    void comPerfilSandboxECargaLigadaOCofreExiste() {
        runner().withPropertyValues("spring.profiles.active=sandbox", "app.sandbox.seed.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(CreditorMfaVault.class));
    }

    @Test
    void sandboxComProdNaoSobe() {
        runner().withPropertyValues("spring.profiles.active=sandbox,prod", "app.sandbox.seed.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void arquivoComPermissao600ESemSegredoNoToString() throws Exception {
        Path file = dir.resolve("creditor-mfa.txt");
        FileCreditorMfaVault vault = new FileCreditorMfaVault(new SandboxProperties(new SandboxProperties.Seed(true, null,
                null, null, null, null, null, null, file, null)));
        CreditorMfaVault.Entry entry = new CreditorMfaVault.Entry("aurora", "hzn-financeiro@credor.exemplo.test",
                "FINANCEIRO", SECRET);
        vault.store(entry);
        vault.store(new CreditorMfaVault.Entry("aurora", "hzn-gestao@credor.exemplo.test", "GESTOR", SECRET));
        // regravar a mesma pessoa substitui a linha
        vault.store(entry);

        assertThat(vault.secret("aurora", "hzn-financeiro@credor.exemplo.test")).contains(SECRET);
        assertThat(vault.secret("boreal", "hzn-financeiro@credor.exemplo.test")).isEmpty();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content.lines().filter(l -> !l.startsWith("#"))).hasSize(2)
                .allSatisfy(l -> assertThat(l.split("\t")).hasSize(5));
        assertThat(content).contains("48656c6c6f21deadbeef48656c6c6f21deadbeef");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        try (var temps = Files.list(dir)) {
            assertThat(temps.map(p -> p.getFileName().toString())).containsExactly("creditor-mfa.txt");
        }

        assertThat(entry.toString()).doesNotContain(SECRET).contains("***");
        assertThat(new CreditorPortalClient.Enrollment(SECRET).toString()).doesNotContain(SECRET);
        assertThat(new CreditorPortalClient.Session("eyJ.token.secreto").toString()).doesNotContain("eyJ");
    }
}
