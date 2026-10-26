package br.com.gestorcobranca.assistant.application.port.out;

import br.com.gestorcobranca.shared.ai.LanguageModel;

/**
 * Porta de saída do assistente de negociação para o modelo de linguagem. O contrato (mensagens, ferramentas,
 * nível de modelo, uso, custo e falhas) é o neutro do kernel ({@link LanguageModel}); esta interface existe para
 * que o assistente tenha o próprio adaptador e a própria configuração ({@code app.assistant.*}), separados de
 * outros produtos de IA (ex.: chat de suporte). Adaptadores: {@code BedrockLanguageModel} (Amazon Bedrock,
 * Converse API, modelos Claude — issue #12) e {@code SandboxLanguageModel} (simulado, local/teste), um por
 * instância. Falha do provedor: o turno termina com transferência para humano (o devedor não fica sem
 * resposta). Ver docs/tecnico/desenho/assistente.md §6.
 */
public interface LanguageModelPort extends LanguageModel {
}
