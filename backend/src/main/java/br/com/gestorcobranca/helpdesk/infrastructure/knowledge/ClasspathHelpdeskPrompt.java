package br.com.gestorcobranca.helpdesk.infrastructure.knowledge;

import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskPrompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Prompt de sistema do chat de suporte: {@code classpath:helpdesk/prompts/suporte-v<versão>.txt}, primeira linha
 * {@code # descrição}. Versão publicada nunca é editada (nova versão = novo arquivo e {@link #VERSION} + 1); o
 * SHA-256 vai para cada resposta registrada. Sem segredos: o arquivo é código-fonte revisado.
 */
@Component
class ClasspathHelpdeskPrompt implements HelpdeskPrompt {

    static final String CODE = "suporte";
    static final int VERSION = 1;

    private final Prompt prompt;

    ClasspathHelpdeskPrompt() {
        String file = "helpdesk/prompts/" + CODE + "-v" + VERSION + ".txt";
        try {
            String raw = new ClassPathResource(file).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            int nl = raw.indexOf('\n');
            if (!raw.startsWith("# ") || nl < 0) {
                throw new IllegalStateException("Prompt sem descrição na primeira linha: " + file);
            }
            String text = raw.substring(nl + 1).strip();
            this.prompt = new Prompt(CODE, VERSION, text, sha256(text));
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt do chat de suporte ausente: " + file, e);
        }
    }

    @Override
    public Prompt current() {
        return prompt;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
