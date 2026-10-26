/**
 * Assistente de IA de atendimento e negociação (F24): "a IA conversa, o motor decide". Política do
 * assistente versionada por assessoria, credor e carteira (modo, canais, horários, perfil de alçada,
 * temas, gatilhos de transferência, mensagens de transparência, limites de custo e tokens);
 * orquestração dos turnos das conversas conduzidas pelo assistente (contexto minimizado, porta
 * {@code LanguageModelPort} com ferramentas que chamam os casos de uso dos módulos donos — o servidor
 * valida toda ação), guardrails de saída, transferência para humano, rascunhos nos modos
 * sombra/copiloto, trilha append-only de cada turno e avaliação por amostragem.
 * <p>
 * Pix na conversa (decisão do responsável; ADR-0009): o assistente gera, pela interface pública do
 * {@code payment} ({@code payment :: usecases}), o Pix imediato da parcela de acordo do próprio devedor
 * quando os critérios objetivos de conversa amigável e conciliação tranquila são atendidos no servidor
 * ({@code PixEligibility}); o código sai em mensagem própria quando a cobrança fica ativa no PSP (evento
 * {@code PaymentInstructionStatusChanged}). O payment não depende do assistant: não há ciclo.
 * <p>
 * Provedor de IA (issue #12): Amazon Bedrock com modelos Claude ({@code app.assistant.bedrock.enabled}; nível
 * de modelo escolhido pelo servidor a cada chamada — padrão na conversa geral, avançado em negociação, proposta,
 * aceite e Pix); em local/teste, um simulador determinístico ({@code app.assistant.sandbox.enabled}, recusado no
 * perfil {@code prod}). Um provedor por instância.
 * Ver {@code docs/tecnico/desenho/assistente.md}.
 */
@ApplicationModule(displayName = "Assistant",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases", "compliance :: usecases",
                "collection :: usecases", "negotiation :: usecases", "communication :: usecases",
                "payment :: usecases"})
package br.com.gestorcobranca.assistant;

import org.springframework.modulith.ApplicationModule;
