package br.com.gestorcobranca.shared.privacy;

import java.util.List;

/**
 * Fonte de "com quem os dados do titular foram compartilhados" (LGPD art. 18, VII), implementada pelo módulo dono do
 * registro (communication, telephony, payment, assistant, legal, publicapi e debt para os credores). É linguagem
 * publicada do kernel, como {@code shared.pendingwork.PendingWorkSource}: o compliance recebe todas as implementações
 * por injeção e não depende dos módulos que as fornecem (eles já dependem do compliance pelo porteiro de contato —
 * depender deles criaria ciclo).
 * <p>
 * Regras para quem implementa:
 * <ul>
 *   <li>Só ids, nomes de empresa/conta, finalidade, período e contagens: nunca conteúdo de mensagem, telefone, e-mail,
 *       documento ou valor do devedor.</li>
 *   <li>Respeitar o escopo por credor da consulta ({@link DataSharingQuery#creditorScope()}); a RLS do tenant vem da
 *       transação, que o agregador abre (somente leitura, uma por fonte).</li>
 *   <li>Consultas servidas por índice do devedor.</li>
 *   <li>A chave do destinatário é contrato com o cadastro de operadores: não renomear.</li>
 * </ul>
 */
public interface DataSharingSource {

    /** Identificador estável da fonte (o módulo dono), ex.: {@code communication}. */
    String id();

    /** Nome da área para exibição, inclusive quando a fonte fica indisponível (ex.: "Mensagens"). */
    String label();

    /** Destinatários com quem dados do devedor foram compartilhados. */
    List<DataSharingFact> sharedWith(DataSharingQuery query);

    /** Prestadores configurados na assessoria que recebem dados de devedores (vazio se a fonte não tem prestador). */
    List<DataSharingRecipient> recipientsInUse();
}
