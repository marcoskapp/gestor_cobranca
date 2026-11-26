package br.com.gestorcobranca.sandbox.infrastructure.file;

import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorMfaVault;
import br.com.gestorcobranca.sandbox.domain.service.DemoTotp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Arquivo de texto (uma linha por pessoa do credor: assessoria, e-mail, perfil, segredo Base32 e a mesma chave em
 * hexadecimal, separados por tabulação) no volume exclusivo do sandbox, regravado de forma atômica com permissão 600 —
 * como o {@link FileDemoTokenVault}. Lido pelos scripts locais com {@code compose exec -T app cat}. Nenhum segredo vai
 * para o log; o {@code toString} da entrada é mascarado.
 */
@SandboxSeedComponent
class FileCreditorMfaVault implements CreditorMfaVault {

    private static final String HEADER = "# Autenticador das pessoas do credor de demonstração do sandbox (dados FICTÍCIOS). "
            + "Segredos gerados na ativação; recriados no reset.\n# assessoria\te-mail\tperfil\tsegredo_base32\tchave_hex\n";

    private final Path file;

    FileCreditorMfaVault(SandboxProperties properties) {
        if (properties.seed().creditorMfaFile() == null) {
            throw new IllegalStateException("app.sandbox.seed.creditor-mfa-file é obrigatório com a carga do sandbox ligada");
        }
        this.file = properties.seed().creditorMfaFile();
    }

    @Override
    public synchronized void store(Entry entry) {
        String key = entry.tenant() + "\t" + entry.email() + "\t";
        List<String> kept = new ArrayList<>();
        for (String line : lines()) {
            if (!line.startsWith(key)) {
                kept.add(line);
            }
        }
        kept.add(String.join("\t", entry.tenant(), entry.email(), entry.role(), entry.secret(),
                DemoTotp.hex(entry.secret())));
        write(HEADER + String.join("\n", kept) + "\n");
    }

    @Override
    public synchronized Optional<String> secret(String tenant, String email) {
        String key = tenant + "\t" + email + "\t";
        return lines().stream().filter(l -> l.startsWith(key)).map(l -> l.split("\t"))
                .filter(f -> f.length >= 4).map(f -> f[3]).findFirst();
    }

    private List<String> lines() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível ler o cofre do autenticador do sandbox", e);
        }
    }

    private void write(String content) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            Path temp = posix
                    ? Files.createTempFile(dir, ".creditor-mfa", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(dir, ".creditor-mfa", ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp); // falha no meio: o temporário com segredos não fica no volume
            }
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível gravar o cofre do autenticador do sandbox", e);
        }
    }
}
