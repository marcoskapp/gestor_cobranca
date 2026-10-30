/**
 * API pública de integração dos credores (docs/tecnico/desenho/api-publica.md): tokens de integração por credor
 * (segredo exibido uma vez, só o HMAC guardado, escopos, limite de taxa, IPs permitidos, revogação imediata e
 * rotação com carência), autenticação própria em {@code /public-api/**} (ator {@code INTEGRATION} com o escopo de um
 * único credor), endpoints {@code /public-api/v1} que <strong>chamam os casos de uso dos módulos donos</strong>
 * (carteiras, importação, títulos, devedores, cobrança, acordos, pagamentos e contestações) sem duplicar regra,
 * medição de consumo por requisição (acumulada em memória e gravada em lote por hora), extrato reproduzível por ciclo
 * de consumo do credor com os preços do contrato (lidos do organization) e detalhamento por token, documentação OpenAPI separada da API interna, registro de versões
 * com cabeçalhos {@code Deprecation}/{@code Sunset} e changelog público.
 * <p>
 * Nome {@code publicapi} (e não {@code integration}): o módulo é a fronteira de entrada para sistemas dos credores;
 * "integração" já designa, no produto, os provedores configurados pela assessoria ({@code INTEGRATION_MANAGE}).
 */
@ApplicationModule(displayName = "Public API",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases", "ingestion :: usecases",
                "collection :: usecases", "negotiation :: usecases", "payment :: usecases",
                "settlement :: usecases"})
package br.com.gestorcobranca.publicapi;

import org.springframework.modulith.ApplicationModule;
