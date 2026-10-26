package br.com.gestorcobranca.shared.pendingwork;

import br.com.gestorcobranca.shared.security.Actor;

import java.util.List;

/**
 * Provedor de pendências do usuário atual ("Minhas pendências"), implementado pelo módulo dono do
 * dado (docs/tecnico/desenho/pendencias.md). É linguagem publicada do kernel, como os eventos de
 * integração: o agregador (módulo {@code pendingwork}) recebe todas as implementações por injeção e
 * não depende dos módulos que as fornecem, de modo que o grafo de dependências não muda.
 * <p>
 * Regras para quem implementa:
 * <ul>
 *   <li>Só contagens e resumos: nunca nome, documento, contato ou valor de devedor.</li>
 *   <li>A visibilidade é a do caso de uso de destino: permissão, escopo por credor
 *       ({@code AccessScope}/RLS) e segregação de funções (quem informou, calculou ou pediu não vê a
 *       própria aprovação).</li>
 *   <li>Consultas de contagem servidas por índice; a fonte roda em transação própria, com limite de
 *       tempo aplicado pelo agregador.</li>
 *   <li>O código do tipo ({@code <modulo>.<pendencia>}) é contrato com a interface e com o log de
 *       negócio: não renomear.</li>
 * </ul>
 */
public interface PendingWorkSource {

    /** Identificador estável da fonte (o módulo dono), ex.: {@code payment}. */
    String id();

    /** Nome da área para exibição, inclusive quando a fonte fica indisponível (ex.: "Pagamentos"). */
    String label();

    /** Se o ator pode ter pendências desta fonte (permissões); falso evita a consulta. */
    boolean appliesTo(Actor actor);

    /** Pendências do ator da requisição; itens com contagem zero são descartados pelo agregador. */
    List<PendingWorkItem> pendingFor(PendingWorkRequest request);
}
