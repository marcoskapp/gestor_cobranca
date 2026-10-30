package br.com.gestorcobranca.sandbox.infrastructure.file;

import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.port.out.DemoPdfShelf.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileDemoPdfShelfTest {

    @TempDir
    Path dir;

    private FileDemoPdfShelf shelf() {
        return new FileDemoPdfShelf(new SandboxProperties(new SandboxProperties.Seed(true, null, null, null, null, null,
                null, dir)));
    }

    @Test
    void storesFilesPerTenantAndKeepsOneIndexForAllTenants() throws Exception {
        FileDemoPdfShelf shelf = shelf();
        assertThat(shelf.exists("aurora", "a.pdf")).isFalse();
        shelf.store("aurora", "a.pdf", new byte[]{1, 2});
        assertThat(shelf.exists("aurora", "a.pdf")).isTrue();
        assertThat(Files.readAllBytes(dir.resolve("aurora/a.pdf"))).containsExactly(1, 2);

        shelf.index("aurora", List.of(new Entry("aurora", "Banco\tHorizonte", "HZN-CARTAO", "a.pdf", "demo")));
        shelf.index("boreal", List.of(new Entry("boreal", "Coop", "CSR-CARTAO", "b.pdf", "demo")));
        shelf.index("aurora", List.of(new Entry("aurora", "Banco Horizonte", "HZN-CARTAO", "c.pdf", "nova")));
        List<String> lines = Files.readAllLines(dir.resolve(FileDemoPdfShelf.INDEX), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith("#")).toList();
        assertThat(lines).containsExactly("boreal\tCoop\tCSR-CARTAO\tb.pdf\tdemo",
                "aurora\tBanco Horizonte\tHZN-CARTAO\tc.pdf\tnova");
    }

    @Test
    void refusesPathsOutsideTheFolder() {
        assertThatThrownBy(() -> shelf().store("aurora", "../x.pdf", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> shelf().store("../etc", "x.pdf", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
