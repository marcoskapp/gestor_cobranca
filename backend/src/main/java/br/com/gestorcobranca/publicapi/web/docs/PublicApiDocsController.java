package br.com.gestorcobranca.publicapi.web.docs;

import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ApiVersionView;
import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ChangelogEntry;
import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ChangelogRelease;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publicação da API pública, sem autenticação e só para a API pública: contrato OpenAPI publicado por versão (o arquivo
 * versionado em {@code docs/api/public-api}), registro de versões, changelog (JSON e página), e a página de referência
 * (Swagger UI servido pela própria aplicação, sem CDN, com "Try it out" desligado). A API interna continua sem
 * documentação pública.
 */
@Hidden
@RestController
@RequestMapping(ApiPaths.PUBLIC_API)
class PublicApiDocsController {

    private static final String SWAGGER = "META-INF/resources/webjars/swagger-ui/";
    private static final Map<String, String> ASSETS = Map.of(
            "swagger-ui.css", "text/css", "swagger-ui-bundle.js", "text/javascript",
            "docs.js", "text/javascript", "docs.css", "text/css");
    private static final Set<String> OWN = Set.of("docs.js", "docs.css");

    private final ApiVersionUseCases versions;
    private final String swaggerVersion;

    PublicApiDocsController(ApiVersionUseCases versions) {
        this.versions = versions;
        this.swaggerVersion = swaggerUiVersion();
    }

    @GetMapping(path = "/{version:v[1-9][0-9]*}/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> contract(@PathVariable String version) {
        String body = versions.contract(version).orElseThrow(() -> new ResourceNotFoundException("api_contract", version));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/versions")
    List<ApiVersionView> versions() {
        return versions.versions();
    }

    @GetMapping("/changelog")
    List<ChangelogRelease> changelog() {
        return versions.changelog();
    }

    @GetMapping(path = {"/docs", "/docs/"}, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<Resource> index() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("publicapi/docs/index.html"));
    }

    @GetMapping("/docs/assets/{name:[a-z-]+\\.(?:css|js)}")
    ResponseEntity<Resource> asset(@PathVariable String name) {
        String type = ASSETS.get(name);
        if (type == null) {
            throw new ResourceNotFoundException("asset", name);
        }
        Resource resource = new ClassPathResource(OWN.contains(name) ? "publicapi/docs/" + name
                : SWAGGER + swaggerVersion + "/" + name);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .contentType(MediaType.parseMediaType(type + ";charset=UTF-8")).body(resource);
    }

    /** Página de gestão de mudanças para o terceiro: versões (com depreciação e desligamento) e changelog. */
    @GetMapping(path = "/docs/changelog", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> changelogPage() {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Changelog — API pública</title><link rel="stylesheet" href="/public-api/docs/assets/docs.css">
                </head><body><header class="gc-docs-header"><strong>Gestor de Cobrança · API pública</strong><nav>
                <a href="/public-api/docs">Referência v1</a><a href="/public-api/docs/changelog">Changelog e versões</a>
                <a href="/public-api/changelog">Changelog (JSON)</a></nav></header><main class="gc-changelog">
                <h1>Versões e changelog</h1><h2>Versões</h2><table><thead><tr><th>Versão</th><th>Situação</th>
                <th>Caminho</th><th>Lançada em</th><th>Depreciada em</th><th>Desligamento (sunset)</th></tr></thead><tbody>
                """);
        for (ApiVersionView v : versions.versions()) {
            html.append("<tr><td>").append(e(v.code())).append("</td><td>").append(status(v.status()))
                    .append("</td><td><code>").append(e(v.basePath())).append("</code></td><td>")
                    .append(e(v.releasedOn())).append("</td><td>").append(e(v.deprecated())).append("</td><td>")
                    .append(e(v.sunset())).append("</td></tr>");
        }
        html.append("</tbody></table><h2>Changelog</h2>");
        for (ChangelogRelease r : versions.changelog()) {
            html.append("<section class=\"release\"><h3>").append(e(r.version())).append(" · ").append(e(r.apiVersion()))
                    .append(" · ").append(e(r.date())).append("</h3><p>").append(e(r.summary())).append("</p>");
            if (r.sunset() != null) {
                html.append("<p><span class=\"gc-badge deprecated\">Desligamento previsto: ").append(e(r.sunset()))
                        .append("</span></p>");
            }
            html.append("<table><thead><tr><th>Tipo</th><th>Mudança</th><th>Endpoint</th></tr></thead><tbody>");
            for (ChangelogEntry entry : r.entries()) {
                html.append("<tr><td>").append(e(entry.type()));
                if (entry.breaking()) {
                    html.append(" <span class=\"gc-badge breaking\">incompatível</span>");
                }
                html.append("</td><td>").append(e(entry.description())).append("</td><td>")
                        .append(entry.endpoint() == null ? "" : "<code>" + e(entry.endpoint()) + "</code>")
                        .append("</td></tr>");
            }
            html.append("</tbody></table></section>");
        }
        html.append("<p>Política de compatibilidade: mudanças compatíveis (novos endpoints, campos opcionais, novos "
                + "valores documentados como extensíveis) entram na versão vigente; mudanças incompatíveis só numa nova "
                + "versão, com a anterior depreciada e mantida até a data de desligamento.</p></main></body></html>");
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.TEXT_HTML).body(html.toString());
    }

    private static String status(String status) {
        return switch (status) {
            case "CURRENT" -> "Vigente";
            case "DEPRECATED" -> "<span class=\"gc-badge deprecated\">Depreciada</span>";
            default -> "Desligada";
        };
    }

    private static String e(Object value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value.toString());
    }

    private static String swaggerUiVersion() {
        try (var in = new ClassPathResource("META-INF/maven/org.webjars/swagger-ui/pom.properties").getInputStream()) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Swagger UI (webjar) ausente", e);
        }
    }
}
