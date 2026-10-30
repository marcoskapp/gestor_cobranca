package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Ponto de extensão — tokens de integração da API pública: integrado após o merge da
 * {@code feature/api-publica}.</strong>
 * <p>
 * A API pública (outra frente, ainda não integrada à master) cria tokens de integração por credor. Quando ela chegar
 * à master, esta etapa passa a emitir, para cada credor de cada assessoria do sandbox, um token de demonstração pelo
 * caso de uso público daquele módulo (nunca gravando no banco por fora), e a documentar o resultado:
 * <ol>
 *   <li>declarar a dependência {@code "<módulo da api pública> :: usecases"} em {@code sandbox/package-info.java};</li>
 *   <li>injetar o caso de uso de tokens aqui e, para cada {@code seed.creditors}, verificar se já há token ativo com o
 *       nome {@code "sandbox-<prefixo do credor>"} (idempotência) e, se não houver, emiti-lo como o administrador da
 *       assessoria ({@code seed.actor("admin")}) — ou o perfil que a API pública definir — com os escopos de importação
 *       de carteira e consulta;</li>
 *   <li>o segredo do token só é exibido na emissão: registrá-lo no log técnico é proibido; expor para a demonstração
 *       pelo mecanismo que a API pública oferecer (ex.: tela de tokens) e citar no guia
 *       {@code docs/tecnico/operacao/sandbox.md} (seção "Tokens de integração").</li>
 * </ol>
 * Até lá a etapa só registra que foi pulada; o {@link SandboxSeeder} já a chama na ordem certa (depois da configuração
 * dos credores e antes das cargas de carteira, para permitir uma carga demonstrativa pela API pública).
 */
@SandboxSeedComponent
class IntegrationTokensSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationTokensSeeder.class);

    void seed(TenantSeed seed) {
        LOG.atInfo().addKeyValue("tenantId", seed.tenantId)
                .log("Tokens de integração da API pública: etapa pendente (integrada após o merge da feature/api-publica)");
    }
}
