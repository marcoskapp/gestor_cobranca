package br.com.gestorcobranca.helpdesk.application.port.out;

import br.com.gestorcobranca.shared.ai.LanguageModel;

/**
 * Porta de saída do chat de suporte para o modelo de linguagem (contrato neutro do kernel, {@link LanguageModel}).
 * Adaptadores: {@code BedrockHelpdeskModel} (Amazon Bedrock, Claude Haiku 5.5, {@code app.helpdesk.bedrock}) e
 * {@code SandboxHelpdeskModel} (simulado determinístico, {@code app.helpdesk.sandbox}; recusado no {@code prod}).
 * No máximo um por instância. Sem nenhum, o chat fica indisponível com mensagem clara (nenhuma chamada é feita).
 */
public interface HelpdeskModelPort extends LanguageModel {
}
