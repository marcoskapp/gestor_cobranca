/**
 * Chat de suporte com IA para o <strong>usuário interno</strong> do portal (docs/tecnico/desenho/chat-suporte.md):
 * ensina a usar a tela em que a pessoa está, com o manual contextual como base de conhecimento (JSON gerado do
 * manual do frontend e lido do classpath), e consulta dados <strong>como o próprio usuário</strong>, por ferramentas
 * somente leitura que chamam os casos de uso dos módulos donos com a permissão e o escopo verificados no servidor
 * — exatamente como nas telas — e registram cada consulta na trilha de acesso. Quando a IA não resolve, prepara um
 * chamado (issue no GitHub) com o texto mascarado no servidor, que a pessoa confirma antes de abrir.
 * <p>
 * Produto diferente do assistente de negociação ({@code assistant}): outro público, prompt, limites, métricas e
 * configuração ({@code app.helpdesk.*}); reaproveita só o cliente de IA do kernel ({@code shared.ai}). Ligação por
 * propriedade, com a mesma guarda de transferência internacional do Bedrock; simulado determinístico em local/teste,
 * recusado no perfil {@code prod}. Nenhum módulo depende deste.
 */
@ApplicationModule(displayName = "Helpdesk",
        allowedDependencies = {"shared", "identity :: usecases", "organization :: usecases", "debt :: usecases",
                "collection :: usecases", "negotiation :: usecases", "payment :: usecases", "ingestion :: usecases",
                "settlement :: usecases", "incentive :: usecases", "pendingwork :: usecases"})
package br.com.gestorcobranca.helpdesk;

import org.springframework.modulith.ApplicationModule;
