package br.com.gestorcobranca.compliance.domain.model;

/**
 * Natureza da comunicação com o devedor no porteiro de contato.
 * <ul>
 *   <li>{@link #COLLECTION}: contato de cobrança (padrão) — todas as regras: restrições, janela, feriados, limites
 *       de frequência e intervalo mínimo; a tentativa conta para os limites.</li>
 *   <li>{@link #TRANSACTIONAL}: resposta devida ao devedor por um pedido dele (ex.: resultado da contestação, CDC):
 *       continuam valendo o opt-out e as demais restrições do devedor, do canal e do contato, a janela de horário,
 *       os feriados e a anonimização; não se aplicam os limites de frequência nem o intervalo mínimo, nem a
 *       suspensão de cobrança pedida pelo credor ({@code CREDITOR_REQUEST}). A tentativa é registrada com a
 *       categoria e não conta para os limites de cobrança.</li>
 *   <li>{@link #RESPONSE}: resposta a contato iniciado pelo devedor que aguarda retorno (mensagem recebida ou pedido de
 *       atendimento pelo portal): não se aplicam os limites de frequência nem o intervalo mínimo — quem procurou espera
 *       ser respondido —, mas valem todas as restrições (inclusive a suspensão pedida pelo credor), a janela, os
 *       feriados e a anonimização. Registrada com a categoria, não conta para os limites de cobrança.</li>
 *   <li>{@link #AUTHENTICATION}: código de acesso pedido pelo próprio devedor para entrar no portal (autenticação, não
 *       contato de cobrança): valem as restrições (descadastro, pedidos do devedor e do credor, bloqueios legais) e a
 *       anonimização; não se aplicam a janela de horário, os feriados, os limites de frequência nem o intervalo
 *       mínimo. Registrada com a categoria, não conta para os limites de cobrança.</li>
 * </ul>
 */
public enum ContactCategory {
    COLLECTION,
    TRANSACTIONAL,
    RESPONSE,
    AUTHENTICATION
}
