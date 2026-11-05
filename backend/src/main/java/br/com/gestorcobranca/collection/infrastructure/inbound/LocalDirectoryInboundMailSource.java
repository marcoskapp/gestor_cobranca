package br.com.gestorcobranca.collection.infrastructure.inbound;

import br.com.gestorcobranca.collection.application.InboundMailProperties;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.InboundMessage;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.Outcome;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.Verdicts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Recebimento simulado para desenvolvimento e sandbox (sem AWS): lê os arquivos {@code *.eml} da pasta configurada,
 * entrega ao mesmo caso de uso do SES e move o arquivo para {@code processados/} (ou {@code falhas/}). Os veredictos
 * do SES são simulados pelos cabeçalhos {@code X-Sandbox-Spf}, {@code -Dkim}, {@code -Dmarc}, {@code -Virus} e
 * {@code -Spam} (ausente = PASS), para exercitar a quarentena. Recusado no perfil {@code prod}.
 */
class LocalDirectoryInboundMailSource implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDirectoryInboundMailSource.class);

    private final InboundMailUseCases inbound;
    private final InboundMailProperties properties;
    private final Path directory;
    private volatile boolean running;
    private volatile Thread worker;

    LocalDirectoryInboundMailSource(InboundMailUseCases inbound, InboundMailProperties properties) {
        this.inbound = inbound;
        this.properties = properties;
        this.directory = Path.of(properties.local().directory());
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("inbound-mail-local").daemon().start(this::loop);
        LOG.atInfo().addKeyValue("directory", directory.toString())
                .log("Leitura simulada das respostas por e-mail (pasta local) iniciada");
    }

    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    private void loop() {
        while (running) {
            try {
                poll();
            } catch (IOException | RuntimeException e) {
                LOG.atWarn().addKeyValue("error", e.getClass().getSimpleName()).log("Falha ao ler a pasta de e-mails");
            }
            try {
                Thread.sleep(properties.local().pollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Processa os arquivos presentes. @return quantidade processada */
    int poll() throws IOException {
        Files.createDirectories(directory);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.eml")) {
            stream.forEach(files::add);
        }
        files.sort(null);
        int done = 0;
        for (Path file : files) {
            String target = "processados";
            try {
                byte[] raw = Files.readAllBytes(file);
                Outcome outcome = inbound.receive(new InboundMessage("local:" + file.getFileName() + ":" + sha256(raw),
                        raw, List.of(), verdicts(raw), Instant.now(), file.toAbsolutePath().toString()));
                LOG.atInfo().addKeyValue("file", file.getFileName().toString()).addKeyValue("outcome", outcome)
                        .log("E-mail simulado processado");
                done++;
            } catch (RuntimeException e) {
                target = "falhas";
                LOG.atWarn().addKeyValue("file", file.getFileName().toString())
                        .addKeyValue("error", e.getClass().getSimpleName()).log("Falha ao processar e-mail simulado");
            }
            Path dir = Files.createDirectories(directory.resolve(target));
            Files.move(file, dir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        return done;
    }

    /** Cabeçalhos de simulação (só o bloco de cabeçalhos é lido). */
    static Verdicts verdicts(byte[] raw) {
        Properties headers = new Properties();
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            end = text.indexOf("\n\n");
        }
        String block = end < 0 ? text : text.substring(0, end);
        for (String line : block.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.regionMatches(true, 0, "X-Sandbox-", 0, 10)) {
                headers.setProperty(line.substring(10, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        return new Verdicts(headers.getProperty("spf", "PASS"), headers.getProperty("dkim", "PASS"),
                headers.getProperty("dmarc", "PASS"), headers.getProperty("virus", "PASS"),
                headers.getProperty("spam", "PASS"));
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
