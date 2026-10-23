/**
 * Operação de cobrança (F06, F07, F21, RB02): casos de cobrança por devedor e carteira com ciclo
 * de vida, elegibilidade para contato, filas configuráveis, atribuição e distribuição de casos,
 * interações com tabulação, tarefas e retornos, promessas de pagamento e contestações.
 * <p>
 * Outros módulos usam somente {@code application.port.in}. Reações a outros módulos acontecem
 * por eventos de integração ({@code shared.events.debt}, {@code .compliance},
 * {@code .organization}, {@code .negotiation}, {@code .payment}). Ver
 * {@code docs/tecnico/desenho/collection.md}.
 */
@ApplicationModule(displayName = "Collection",
        allowedDependencies = {"shared", "organization :: usecases", "identity :: usecases", "debt :: usecases",
                "compliance :: usecases"})
package br.com.gestorcobranca.collection;

import org.springframework.modulith.ApplicationModule;
