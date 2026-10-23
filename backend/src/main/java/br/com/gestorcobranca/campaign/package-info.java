/**
 * Módulo campaign (M04): segmentos, campanhas e réguas versionadas (F13), execução por jobs com
 * controle de concorrência entre campanhas, atribuição de resultados e testes A/B (F25), RB07.
 * Envio sempre pelo {@code communication} (que passa pelo {@code ContactGate}); casos, tarefas e
 * dono do atendimento pelo {@code collection}. Ver {@code docs/tecnico/desenho/campanhas.md}.
 */
@ApplicationModule(displayName = "Campaign",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases", "compliance :: usecases",
                "collection :: usecases", "negotiation :: usecases", "communication :: usecases"})
package br.com.gestorcobranca.campaign;

import org.springframework.modulith.ApplicationModule;
