package br.com.gestorcobranca.support;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de contrato por snapshot (RS-26): o conjunto de caminhos dos campos de uma resposta JSON ({@code a.b},
 * {@code lista[].c}), com os campos nulos incluídos, comparado com o arquivo em {@code src/test/resources/contracts/}.
 * Campo novo numa resposta do portal do credor só passa com o snapshot atualizado — uma decisão explícita.
 */
public final class JsonContract {

    private JsonContract() {
    }

    public static Set<String> paths(JsonNode node) {
        Set<String> paths = new TreeSet<>();
        collect(node, "", paths);
        return paths;
    }

    /** Compara com o snapshot (uma linha por caminho; linhas com {@code #} são comentários). */
    public static void assertMatches(JsonNode node, String snapshot) {
        assertThat(paths(node)).as("contrato de " + snapshot + " (atualize o snapshot só com decisão)")
                .containsExactlyElementsOf(expected(snapshot));
    }

    static Set<String> expected(String snapshot) {
        try (InputStream in = JsonContract.class.getResourceAsStream("/contracts/" + snapshot)) {
            if (in == null) {
                throw new IllegalStateException("snapshot ausente: contracts/" + snapshot);
            }
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")).map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void collect(JsonNode node, String prefix, Set<String> paths) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                String path = prefix.isEmpty() ? name : prefix + "." + name;
                paths.add(path);
                collect(node.get(name), path, paths);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), prefix + "[]", paths);
            }
        }
    }
}
