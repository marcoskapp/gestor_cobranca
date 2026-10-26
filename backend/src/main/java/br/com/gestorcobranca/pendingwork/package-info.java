/**
 * Minhas pendências (work inbox) — {@code docs/tecnico/desenho/pendencias.md}: o que o usuário da
 * sessão pode fazer agora (aprovações, prazos, filas), agregado por tipo, com contagem, item mais
 * antigo, prazo mais próximo, severidade e destino na interface.
 * <p>
 * O módulo não conhece os donos dos dados: cada módulo implementa o contrato
 * {@code shared.pendingwork.PendingWorkSource} (linguagem publicada do kernel) e o agregador recebe as
 * implementações por injeção. Assim o grafo de dependências não muda e uma fonte nova não altera este
 * módulo. Cada fonte roda em transação somente leitura própria, num executor limitado, com limite de
 * tempo; a falha de uma não impede as demais (a resposta marca o que ficou indisponível).
 * <p>
 * Só contagens e resumos, nunca dados pessoais. O log de negócio registra uma observação por
 * usuário, tipo e dia ({@code pending_work.observed}) para medir quais pendências envelhecem.
 */
@ApplicationModule(displayName = "Pending work", allowedDependencies = {"shared"})
package br.com.gestorcobranca.pendingwork;

import org.springframework.modulith.ApplicationModule;
