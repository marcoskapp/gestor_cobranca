package br.com.gestorcobranca.sandbox.infrastructure.file;

import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.DemoPdfShelf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PDFs fictícios de demonstração em {@code <pdf-dir>/<assessoria>/<arquivo>.pdf} (volume exclusivo do sandbox) e o
 * índice {@code <pdf-dir>/index.tsv} (assessoria, credor, carteira, arquivo, finalidade — separados por tabulação), lido
 * pelo {@code scripts/sandbox/pdfs.sh}. Gravação atômica; nomes restritos a letras minúsculas, dígitos e hífen.
 */
@SandboxSeedComponent
class FileDemoPdfShelf implements DemoPdfShelf {

    static final String INDEX = "index.tsv";
    private static final String HEADER = "# PDFs FICTÍCIOS de demonstração da Importação PDF (sandbox). Recriados no reset.\n"
            + "# assessoria\tcredor\tcarteira\tarquivo\tfinalidade\n";
    private static final Pattern SAFE = Pattern.compile("[a-z0-9][a-z0-9-]*(\\.pdf)?");

    private final Path root;

    FileDemoPdfShelf(SandboxProperties properties) {
        if (properties.seed().pdfDir() == null) {
            throw new IllegalStateException("app.sandbox.seed.pdf-dir é obrigatório com a carga do sandbox ligada");
        }
        this.root = properties.seed().pdfDir().toAbsolutePath();
    }

    @Override
    public boolean exists(String tenant, String fileName) {
        return Files.isRegularFile(path(tenant, fileName));
    }

    @Override
    public synchronized void store(String tenant, String fileName, byte[] content) {
        write(path(tenant, fileName), content);
    }

    @Override
    public synchronized void index(String tenant, List<Entry> entries) {
        safe(tenant);
        List<String> kept = new ArrayList<>();
        Path index = root.resolve(INDEX);
        if (Files.exists(index)) {
            try {
                for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && !line.startsWith("#") && !line.startsWith(tenant + "\t")) {
                        kept.add(line);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("não foi possível ler o índice dos PDFs do sandbox", e);
            }
        }
        for (Entry e : entries) {
            kept.add(String.join("\t", e.tenant(), clean(e.creditor()), e.portfolioCode(), safe(e.fileName()),
                    clean(e.purpose())));
        }
        write(index, (HEADER + String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private Path path(String tenant, String fileName) {
        return root.resolve(safe(tenant)).resolve(safe(fileName));
    }

    private static String safe(String name) {
        if (name == null || !SAFE.matcher(name).matches()) {
            throw new IllegalArgumentException("nome inválido na pasta de PDFs do sandbox");
        }
        return name;
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ');
    }

    private static void write(Path target, byte[] content) {
        try {
            Path dir = target.getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, ".demo-pdf", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível gravar na pasta de PDFs do sandbox", e);
        }
    }
}
