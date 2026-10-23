/**
 * Portal de autonegociação do devedor (F19) — ver docs/tecnico/desenho/portal.md e
 * docs/negocio/portal-autonegociacao.md.
 * <ul>
 *   <li><b>Acesso seguro</b> (ator {@code DEBTOR}): link de uso único enviado pelo communication, ou
 *       CPF/CNPJ + código (OTP) no contato cadastrado, com desafio de conhecimento opcional por credor;
 *       limite por IP e bloqueio progressivo por documento e IP; respostas idênticas para documento
 *       inexistente (anti-enumeração); sessão curta própria (JWT {@code typ=portal} com audiência
 *       separada, sessão validada no servidor, revogável); log de acesso {@code PORTAL_*}; aviso de
 *       privacidade registrado no compliance por evento.</li>
 *   <li><b>Consulta e negociação</b>: dívidas do próprio devedor nas carteiras habilitadas do credor,
 *       saldo atualizado e ofertas pelo motor do negotiation com a alçada do canal (perfil
 *       {@code PORTAL}) — o mesmo {@code NegotiationGuard} dos humanos e do assistente —, aceite com
 *       termo (canal PORTAL, IP, agente, instante, hash), formalização e Pix da entrada.</li>
 *   <li><b>Operação</b>: pedidos que fogem do portal viram conversa na fila humana
 *       ({@code HandoffRequested}); funil {@code portal.*}; configuração por credor ({@code PORTAL_MANAGE}).</li>
 * </ul>
 * Dependências: {@code negotiation}, {@code payment}, {@code communication}, {@code debt},
 * {@code organization} (interfaces {@code usecases}).
 */
@ApplicationModule(displayName = "Portal",
        allowedDependencies = {"shared", "negotiation :: usecases", "payment :: usecases", "communication :: usecases",
                "debt :: usecases", "organization :: usecases"})
package br.com.gestorcobranca.portal;

import org.springframework.modulith.ApplicationModule;
