package br.com.gestorcobranca.publicapi.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Registro das versões da API pública e do changelog público, mantidos em arquivos versionados no repositório
 * ({@code classpath:publicapi/versions.json} e {@code publicapi/changelog.json}). Consultado sem autenticação (página
 * pública) e pelo filtro que emite {@code Deprecation}/{@code Sunset}.
 */
public interface ApiVersionUseCases {

    List<ApiVersionView> versions();

    Optional<ApiVersionView> version(String code);

    List<ChangelogRelease> changelog();

    /**
     * Contrato OpenAPI publicado da versão (JSON), o mesmo arquivo versionado no repositório
     * ({@code docs/api/public-api/<versão>/openapi.json}); vazio se a versão não tem contrato publicado.
     */
    Optional<String> contract(String code);

    /**
     * @param status     CURRENT, DEPRECATED ou RETIRED
     * @param deprecated início da depreciação (cabeçalho {@code Deprecation}, RFC 9745)
     * @param sunset     desligamento previsto (cabeçalho {@code Sunset}, RFC 8594)
     */
    record ApiVersionView(String code, String status, String basePath, LocalDate releasedOn, Instant deprecated,
                          Instant sunset, String migrationGuide) {
    }

    /** @param type ADDED, CHANGED, DEPRECATED, REMOVED, FIXED ou SECURITY */
    record ChangelogEntry(String type, String description, String endpoint, boolean breaking) {
    }

    record ChangelogRelease(String version, String apiVersion, LocalDate date, String summary,
                            List<ChangelogEntry> entries, LocalDate sunset) {
    }
}
