/**
 * Comunicação (M05, F12, RB07): contas de canal (WhatsApp, e-mail, SMS) com credenciais por
 * assessoria e remetente por credor, templates versionados com variáveis tipadas e aprovação do
 * WhatsApp, conversas com dono do atendimento (assistente, fila ou operador) e transferências,
 * caixa de entrada, mensagens com envio assíncrono sempre autorizado pelo porteiro de contato do
 * compliance, webhooks de provedores e rascunhos do assistente.
 * <p>
 * Outros módulos usam somente {@code application.port.in}. O dono do atendimento de conversas com
 * caso é o do caso ({@code collection}); reações no sentido contrário ao grafo chegam por eventos
 * ({@code shared.events.collection}, {@code shared.events.communication.HandoffRequested}). Ver
 * {@code docs/tecnico/desenho/comunicacao.md}.
 */
@ApplicationModule(displayName = "Communication",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases", "compliance :: usecases",
                "collection :: usecases"})
package br.com.gestorcobranca.communication;

import org.springframework.modulith.ApplicationModule;
