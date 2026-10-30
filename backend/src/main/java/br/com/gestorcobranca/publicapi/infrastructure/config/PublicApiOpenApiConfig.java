package br.com.gestorcobranca.publicapi.infrastructure.config;

import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.publicapi.web.security.PublicApiRequestFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Contrato OpenAPI da API pública v1 (grupo {@code public-v1} do springdoc), separado da API interna: informações,
 * guia de autenticação, esquema do token, escopos, erros RFC 9457, cabeçalhos de limite de taxa, {@code ETag} e
 * {@code X-Request-Id} em todas as operações. O contrato gerado é comparado com o arquivo versionado
 * {@code docs/api/public-api/v1/openapi.json} por teste (mudança sem atualizar o arquivo falha o build).
 */
@Configuration
class PublicApiOpenApiConfig {

    static final String GROUP_V1 = "public-v1";
    static final String SECURITY_SCHEME = "integrationToken";
    private static final String PROBLEM = "Problem";

    @Bean
    GroupedOpenApi publicApiV1() {
        return GroupedOpenApi.builder().group(GROUP_V1).displayName("API pública v1")
                .pathsToMatch("/public-api/v1/**").pathsToExclude("/public-api/v1/openapi.json")
                .addOpenApiCustomizer(this::customize).build();
    }

    void customize(OpenAPI api) {
        api.setInfo(new Info().title("Gestor de Cobrança — API pública de integração").version("1.0.0")
                .description(DESCRIPTION).contact(new Contact().name("Suporte da assessoria"))
                .summary("Administração da carteira de cobrança do credor por integração sistema a sistema"));
        api.setServers(List.of(new Server().url("/").description("Mesmo endereço desta documentação")));
        api.setExternalDocs(new ExternalDocumentation().description("Changelog e versões")
                .url("/public-api/docs/changelog"));
        Components components = api.getComponents() == null ? new Components() : api.getComponents();
        components.setSecuritySchemes(new LinkedHashMap<>(Map.of(SECURITY_SCHEME, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("gck_<prefixo>.<segredo>")
                .description("Token de integração do credor. Envie `Authorization: Bearer gck_…`. Cada endpoint exige "
                        + "um escopo; escopos disponíveis: " + Arrays.stream(TokenScope.values())
                        .map(s -> "`" + s.code() + "` (" + s.description() + ")").collect(Collectors.joining(", "))))));
        components.addSchemas(PROBLEM, problemSchema());
        components.addHeaders("X-Request-Id", header("Identificador da requisição (informe ao suporte)", new StringSchema()));
        components.addHeaders(PublicApiRequestFilter.RATE_LIMIT_LIMIT,
                header("Requisições permitidas por minuto para o token", new IntegerSchema()));
        components.addHeaders(PublicApiRequestFilter.RATE_LIMIT_REMAINING,
                header("Requisições restantes na janela atual", new IntegerSchema()));
        components.addHeaders(PublicApiRequestFilter.RATE_LIMIT_RESET,
                header("Segundos até a próxima janela", new IntegerSchema()));
        components.addHeaders("Retry-After", header("Segundos a aguardar antes de tentar de novo (429)", new IntegerSchema()));
        components.addHeaders("ETag", header("Versão do recurso; envie em If-Match para alterá-lo", new StringSchema()));
        components.addHeaders("Deprecation", header("Presente quando a versão da API está depreciada (RFC 9745)",
                new StringSchema()));
        components.addHeaders("Sunset", header("Data de desligamento da versão depreciada (RFC 8594)", new StringSchema()));
        sortComponents(components);
        api.setComponents(components);
        api.setSecurity(List.of(new SecurityRequirement().addList(SECURITY_SCHEME)));
        api.setTags(List.of(
                new Tag().name("Carteiras").description("Credor do token, contratos de serviço e carteiras"),
                new Tag().name("Importação").description("Carga de títulos em lote, assíncrona e idempotente"),
                new Tag().name("Títulos").description("Consulta, sincronização e ciclo de vida dos títulos"),
                new Tag().name("Devedores").description("Consulta mascarada e contatos"),
                new Tag().name("Cobrança").description("Casos, acordos e contestações"),
                new Tag().name("Pagamentos").description("Consulta e pagamento informado pelo credor")));
        if (api.getPaths() != null) {
            api.getPaths().values().forEach(item -> item.readOperationsMap()
                    .forEach((method, op) -> decorate(op, method == io.swagger.v3.oas.models.PathItem.HttpMethod.GET)));
        }
    }

    private static void decorate(Operation op, boolean read) {
        ApiResponses responses = op.getResponses() == null ? new ApiResponses() : op.getResponses();
        responses.forEach((code, response) -> {
            if (code.startsWith("2")) {
                withHeaders(response, "X-Request-Id", PublicApiRequestFilter.RATE_LIMIT_LIMIT,
                        PublicApiRequestFilter.RATE_LIMIT_REMAINING, PublicApiRequestFilter.RATE_LIMIT_RESET,
                        "Deprecation", "Sunset");
            }
        });
        if (op.getParameters() != null && op.getParameters().stream().anyMatch(p -> "If-Match".equals(p.getName()))) {
            problem(responses, "412", "O recurso mudou desde a leitura (If-Match diferente do ETag atual)");
            responses.values().stream().filter(r -> r.getHeaders() != null).forEach(r -> withHeaders(r, "ETag"));
        }
        problem(responses, "400", "Requisição malformada ou dados inválidos (`validation.failed`, `request.malformed`)");
        problem(responses, "401", "Token ausente, inválido, revogado ou expirado (`auth.unauthenticated`)");
        problem(responses, "403", "Escopo insuficiente, IP não permitido ou credor inativo (`access.denied`)");
        problem(responses, "404", "Recurso inexistente ou de outro credor (`<recurso>.not_found`)");
        if (!read) {
            problem(responses, "409", "Conflito de estado ou alteração concorrente (`<recurso>.<motivo>`)");
            problem(responses, "422", "Regra de negócio violada (`<recurso>.<motivo>`)");
        }
        ApiResponse throttled = problem(responses, "429", "Limite de requisições do token excedido (`rate_limited`)");
        withHeaders(throttled, "Retry-After", PublicApiRequestFilter.RATE_LIMIT_LIMIT,
                PublicApiRequestFilter.RATE_LIMIT_REMAINING, PublicApiRequestFilter.RATE_LIMIT_RESET);
        problem(responses, "500", "Erro interno (`internal_error`); informe o `requestId` ao suporte");
        TreeMap<String, ApiResponse> sorted = new TreeMap<>(responses);
        ApiResponses ordered = new ApiResponses();
        sorted.forEach(ordered::addApiResponse);
        op.setResponses(ordered);
    }

    private static ApiResponse problem(ApiResponses responses, String code, String description) {
        ApiResponse response = responses.get(code);
        if (response == null) {
            response = new ApiResponse().description(description);
            responses.addApiResponse(code, response);
        }
        response.setContent(new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
        return response;
    }

    private static void withHeaders(ApiResponse response, String... names) {
        Map<String, Header> headers = response.getHeaders() == null ? new TreeMap<>() : new TreeMap<>(response.getHeaders());
        for (String name : names) {
            headers.put(name, new Header().$ref("#/components/headers/" + name));
        }
        response.setHeaders(headers);
    }

    private static Header header(String description, Schema<?> schema) {
        return new Header().description(description).schema(schema);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void sortComponents(Components c) {
        if (c.getSchemas() != null) {
            c.setSchemas(new TreeMap<>(c.getSchemas()));
        }
        if (c.getHeaders() != null) {
            c.setHeaders(new TreeMap<>(c.getHeaders()));
        }
    }

    private static Schema<?> problemSchema() {
        ObjectSchema s = new ObjectSchema();
        s.setDescription("Erro no formato RFC 9457 (application/problem+json)");
        s.addProperty("type", new StringSchema().description("URI do tipo do problema"));
        s.addProperty("title", new StringSchema().description("Resumo do status HTTP"));
        s.addProperty("status", new IntegerSchema().description("Status HTTP"));
        s.addProperty("detail", new StringSchema().description("Explicação legível, em português"));
        s.addProperty("code", new StringSchema().description("Código estável do erro, para tratamento automático")
                .example("debt_title.version_mismatch"));
        s.addProperty("requestId", new StringSchema().description("Identificador da requisição para o suporte"));
        s.addProperty("details", new ObjectSchema().description("Dados adicionais do erro, quando houver"));
        s.addProperty("errors", new io.swagger.v3.oas.models.media.ArraySchema().items(new ObjectSchema()
                .addProperty("field", new StringSchema()).addProperty("message", new StringSchema()))
                .description("Campos inválidos (validation.failed)"));
        s.setRequired(List.of("status", "code"));
        return s;
    }

    private static final String DESCRIPTION = """
            API para o **credor** administrar, por integração sistema a sistema, a carteira de cobrança mantida na \
            assessoria: carteiras, importação de títulos, ciclo de vida dos títulos, contatos dos devedores, \
            acompanhamento da cobrança, acordos e pagamentos, pagamento informado e contestações — e acompanhar, a \
            qualquer momento, o consumo da própria API e o repasse da prestação de contas.

            ## Autenticação
            Cada credor recebe da assessoria um **token de integração** (`gck_<prefixo>.<segredo>`), exibido uma \
            única vez. Envie em todas as chamadas: `Authorization: Bearer gck_…`. O token pertence a um único \
            credor: os dados de outros credores não existem para ele. Guarde-o num cofre de segredos; nunca em código, \
            URL ou log. Em caso de suspeita de vazamento, peça a revogação (imediata). A rotação gera novo segredo e \
            mantém o anterior válido pela carência escolhida, para a troca sem parada.

            ## Escopos
            Cada endpoint exige um escopo concedido ao token (ex.: `titles:read`). Sem o escopo: 403.

            ## Limite de requisições
            Por token, em janelas de 1 minuto. Toda resposta traz `RateLimit-Limit`, `RateLimit-Remaining` e \
            `RateLimit-Reset`; acima do limite: 429 com `Retry-After`.

            ## Erros
            Formato RFC 9457 (`application/problem+json`) com `code` estável e `requestId`.

            ## Sincronização incremental
            Listas usam **paginação por cursor** em ordem crescente de atualização. Guarde o `nextCursor` da última \
            página e continue dele na próxima sincronização; `hasMore=true` indica que há mais agora. Alterações \
            muito recentes (último minuto) aparecem na chamada seguinte.

            ## Concorrência otimista
            Leituras de carteira e título trazem `ETag`; envie-o em `If-Match` ao alterar. Se o recurso mudou: 412.

            ## Idempotência
            Comandos que criam lotes ou pagamentos exigem `Idempotency-Key` (8 a 80 caracteres). Repetir a chamada com \
            a mesma chave e o mesmo conteúdo devolve o mesmo resultado; com conteúdo diferente: 409.

            ## Posição do faturamento e do repasse
            `GET /billing/position` (escopo `billing:read`) mostra o ciclo de consumo em andamento: chamadas \
            cobráveis (2xx/3xx), MB inteiros (1 MB = 1.048.576 bytes, arredondados por token), valor até agora, \
            franquias, crescimento diário e projeção; o consumo é gravado em lotes de cerca de 1 minuto \
            (`updatedAt`). `GET /settlement/position` (escopo `settlement:read`) estima o repasse do período em \
            aberto como o demonstrativo calcularia se fechado agora — é uma estimativa, não um demonstrativo; os \
            demonstrativos enviados estão em `GET /settlement/statements`.

            ## Dados pessoais (LGPD)
            Documento e contatos do devedor são sempre devolvidos mascarados. Envie só os dados necessários.

            ## Versões
            Versão na URL (`/public-api/v1`). Mudanças compatíveis entram na mesma versão; incompatíveis, numa nova. \
            Versões depreciadas respondem com `Deprecation` e `Sunset`. Acompanhe em `/public-api/docs/changelog`.
            """;
}
