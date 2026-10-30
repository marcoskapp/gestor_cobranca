package br.com.gestorcobranca.sandbox.infrastructure.file;

import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.DemoTokenVault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Arquivo de texto (uma linha por token, campos separados por tabulação) no volume exclusivo do sandbox, regravado
 * de forma atômica com permissão 600. Lido pelo {@code scripts/sandbox/tokens.sh}. Nenhum segredo vai para o log.
 */
@SandboxSeedComponent
class FileDemoTokenVault implements DemoTokenVault {

    private static final String HEADER = "# Tokens de demonstração da API pública do sandbox (dados FICTÍCIOS). Segredos exibidos só na "
            + "emissão; recriados no reset.\n# token_id\tassessoria\tcredor\tnome\tescopos\ttoken\n";

    private final Path file;

    FileDemoTokenVault(SandboxProperties properties) {
        if (properties.seed().tokensFile() == null) {
            throw new IllegalStateException("app.sandbox.seed.tokens-file é obrigatório com a carga do sandbox ligada");
        }
        this.file = properties.seed().tokensFile();
    }

    @Override
    public synchronized Set<UUID> storedTokenIds() {
        Set<UUID> ids = new HashSet<>();
        for (String line : lines()) {
            ids.add(UUID.fromString(line.substring(0, line.indexOf('\t'))));
        }
        return ids;
    }

    @Override
    public synchronized void store(Entry entry) {
        List<String> kept = new ArrayList<>();
        for (String line : lines()) {
            if (!line.startsWith(entry.tokenId() + "\t")) {
                kept.add(line);
            }
        }
        kept.add(String.join("\t", entry.tokenId().toString(), entry.tenant(), entry.creditor(), entry.name(),
                String.join(",", entry.scopes()), entry.token()));
        write(HEADER + String.join("\n", kept) + "\n");
    }

    private List<String> lines() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível ler o arquivo de tokens do sandbox", e);
        }
    }

    private void write(String content) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            Path temp = posix
                    ? Files.createTempFile(dir, ".api-tokens", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(dir, ".api-tokens", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível gravar o arquivo de tokens do sandbox", e);
        }
    }
}
