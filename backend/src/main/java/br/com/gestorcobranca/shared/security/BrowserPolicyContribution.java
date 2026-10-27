package br.com.gestorcobranca.shared.security;

import java.util.List;

/**
 * Acréscimo de um módulo à política do navegador servida com a aplicação (CSP e
 * {@code Permissions-Policy}), declarado como bean e reunido pela cadeia de segurança (módulo
 * identity), como {@link PublicEndpoint}. Use somente origens exatas de fornecedores necessárias a
 * um recurso ligado (ex.: SDK de voz WebRTC empacotado no build); {@code script-src} nunca é
 * ampliado.
 *
 * @param connectSrc    origens acrescentadas a {@code connect-src} (ex.: {@code wss://voice-js.roaming.twilio.com})
 * @param mediaSrc      origens acrescentadas a {@code media-src}
 * @param selfFeatures  recursos liberados para a própria origem na {@code Permissions-Policy} (ex.: {@code microphone})
 * @param reason        justificativa documental (log de inicialização)
 */
public record BrowserPolicyContribution(List<String> connectSrc, List<String> mediaSrc, List<String> selfFeatures,
                                        String reason) {

    public BrowserPolicyContribution {
        connectSrc = connectSrc == null ? List.of() : List.copyOf(connectSrc);
        mediaSrc = mediaSrc == null ? List.of() : List.copyOf(mediaSrc);
        selfFeatures = selfFeatures == null ? List.of() : List.copyOf(selfFeatures);
        for (String origin : concat(connectSrc, mediaSrc)) {
            if (!origin.matches("(https|wss)://[a-z0-9.\\-]+(:\\d{2,5})?")) {
                throw new IllegalArgumentException("Origem inválida para a política do navegador: " + origin);
            }
        }
        for (String feature : selfFeatures) {
            if (!feature.matches("[a-z\\-]{3,40}")) {
                throw new IllegalArgumentException("Recurso inválido para a Permissions-Policy: " + feature);
            }
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
