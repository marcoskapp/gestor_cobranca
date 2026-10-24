package br.com.gestorcobranca.assistant.application.port.out;

import java.util.List;

/**
 * Prompts versionados do assistente (recursos do módulo, sem segredos). Cada versão é imutável: alterar
 * o texto exige um arquivo de versão nova; a política escolhe a versão e a troca fica na auditoria da
 * publicação da política.
 */
public interface PromptSource {

    List<PromptTemplate> all();

    /**
     * @param sha256 impressão digital do texto (registrada em cada turno)
     */
    record PromptTemplate(String code, int version, String description, String text, String sha256) {
    }
}
