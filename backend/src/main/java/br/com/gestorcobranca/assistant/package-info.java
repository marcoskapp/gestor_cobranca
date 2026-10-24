/**
 * Assistente de IA de atendimento e negociação (F24): "a IA conversa, o motor decide". Política do
 * assistente versionada por assessoria, credor e carteira (modo, canais, horários, perfil de alçada,
 * temas, gatilhos de transferência, mensagens de transparência, limites de custo e tokens);
 * orquestração dos turnos das conversas conduzidas pelo assistente (contexto minimizado, porta
 * {@code LanguageModelPort} com ferramentas que chamam os casos de uso dos módulos donos — o servidor
 * valida toda ação), guardrails de saída, transferência para humano, rascunhos nos modos
 * sombra/copiloto, trilha append-only de cada turno e avaliação por amostragem.
 * <p>
 * O provedor de IA ainda não foi escolhido (issue #12): em produção não há adaptador; em local/teste
 * um simulador determinístico ({@code app.assistant.sandbox.enabled}, recusado no perfil {@code prod}).
 * Ver {@code docs/tecnico/desenho/assistente.md}.
 */
@ApplicationModule(displayName = "Assistant",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases", "compliance :: usecases",
                "collection :: usecases", "negotiation :: usecases", "communication :: usecases"})
package br.com.gestorcobranca.assistant;

import org.springframework.modulith.ApplicationModule;
