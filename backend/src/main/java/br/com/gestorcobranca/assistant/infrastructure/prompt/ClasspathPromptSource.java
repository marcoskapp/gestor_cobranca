package br.com.gestorcobranca.assistant.infrastructure.prompt;

import br.com.gestorcobranca.assistant.application.port.out.PromptSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompts versionados como recursos do módulo: {@code classpath:assistant/prompts/<código>-v<versão>.txt},
 * primeira linha {@code # descrição}, demais linhas o texto. Uma versão publicada nunca é editada — o
 * SHA-256 do texto vai para cada turno e uma alteração aparece como impressão digital diferente.
 * Sem segredos: o arquivo é parte do código-fonte revisado.
 */
@Component
class ClasspathPromptSource implements PromptSource {

    private static final Pattern NAME = Pattern.compile("([a-z0-9-]+)-v(\\d+)\\.txt");

    private final List<PromptTemplate> prompts;

    ClasspathPromptSource() {
        this.prompts = load();
    }

    @Override
    public List<PromptTemplate> all() {
        return prompts;
    }

    private static List<PromptTemplate> load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:assistant/prompts/*.txt");
            List<PromptTemplate> out = new ArrayList<>();
            for (Resource r : resources) {
                Matcher m = NAME.matcher(r.getFilename() == null ? "" : r.getFilename());
                if (!m.matches()) {
                    throw new IllegalStateException("Nome de prompt inválido: " + r.getFilename());
                }
                String raw = r.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
                int nl = raw.indexOf('\n');
                if (!raw.startsWith("# ") || nl < 0) {
                    throw new IllegalStateException("Prompt sem descrição na primeira linha: " + r.getFilename());
                }
                String text = raw.substring(nl + 1).strip();
                out.add(new PromptTemplate(m.group(1), Integer.parseInt(m.group(2)), raw.substring(2, nl).strip(), text,
                        sha256(text)));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao carregar os prompts do assistente", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
