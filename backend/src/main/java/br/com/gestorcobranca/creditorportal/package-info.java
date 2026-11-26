/**
 * API de negócio do portal do credor (ADR 0011; docs/tecnico/desenho/portal-credor.md): o que a pessoa do credor vê e
 * responde — prestação de contas (fatia 4), contestações (fatia 5), pagamento direto informado (fatia 6), o Início com
 * os indicadores do credor e o "Aguardando você", e a própria atividade (fatia 7). No topo do grafo, como o {@code publicapi}: só consome casos de uso dos módulos donos (métodos
 * próprios do credor, que exigem o ator {@code CREDITOR}) e a nova verificação do MFA do {@code identity :: creditor} — nunca as portas da assessoria
 * ({@code settlement :: creditor}, não {@code usecases}).
 * <p>
 * Respostas por lista de campos permitidos: os records desta porta e da web são próprios (nunca os de {@code port/in}
 * dos outros módulos — {@code ArchitectureTest}), para que um campo novo num módulo dono não chegue ao credor sem
 * decisão. Sem tabela própria no MVP (faixa V2600–V2699 reservada).
 */
@ApplicationModule(displayName = "Creditor portal",
        allowedDependencies = {"shared", "identity :: creditor", "settlement :: creditor", "collection :: creditor",
                "payment :: creditor", "analytics :: creditor"})
package br.com.gestorcobranca.creditorportal;

import org.springframework.modulith.ApplicationModule;
