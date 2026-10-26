package br.com.gestorcobranca.helpdesk.application.port.out;

/**
 * Prompt de sistema versionado do chat de suporte ({@code classpath:helpdesk/prompts/<código>-v<versão>.txt}).
 * Versão publicada nunca é editada: mudança = arquivo novo. O SHA-256 vai para cada resposta registrada.
 */
public interface HelpdeskPrompt {

    Prompt current();

    record Prompt(String code, int version, String text, String sha256) {

        public String reference() {
            return code + "@" + version;
        }
    }
}
