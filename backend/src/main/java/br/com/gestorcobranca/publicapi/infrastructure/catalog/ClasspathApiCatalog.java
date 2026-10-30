package br.com.gestorcobranca.publicapi.infrastructure.catalog;

import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ApiVersionView;
import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ChangelogEntry;
import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ChangelogRelease;
import br.com.gestorcobranca.publicapi.application.port.out.ApiCatalog;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Lê {@code publicapi/versions.json} e {@code publicapi/changelog.json} do classpath na subida (arquivos versionados no
 * repositório; a subida falha se estiverem inválidos). Exatamente uma versão {@code CURRENT}; versão
 * {@code DEPRECATED} exige data de depreciação e de desligamento.
 */
@Component
class ClasspathApiCatalog implements ApiCatalog {

    private static final Set<String> STATUSES = Set.of("CURRENT", "DEPRECATED", "RETIRED");
    private static final Set<String> TYPES = Set.of("ADDED", "CHANGED", "DEPRECATED", "REMOVED", "FIXED", "SECURITY");

    record VersionsFile(String comment, List<VersionEntry> versions) {
    }

    record VersionEntry(String code, String status, String basePath, LocalDate releasedOn, Instant deprecated,
                        Instant sunset, String migrationGuide) {
    }

    record ChangelogFile(String comment, List<ChangelogRelease> releases) {
    }

    private final List<ApiVersionView> versions;
    private final List<ChangelogRelease> changelog;

    ClasspathApiCatalog(JsonCodec json) {
        VersionsFile v = json.fromJson(read("publicapi/versions.json"), VersionsFile.class);
        ChangelogFile c = json.fromJson(read("publicapi/changelog.json"), ChangelogFile.class);
        this.versions = v.versions().stream().map(ClasspathApiCatalog::validated).toList();
        if (versions.stream().filter(x -> x.status().equals("CURRENT")).count() != 1) {
            throw new IllegalStateException("publicapi/versions.json deve ter exatamente uma versão CURRENT");
        }
        this.changelog = c.releases().stream().map(ClasspathApiCatalog::validated).toList();
    }

    private static ApiVersionView validated(VersionEntry e) {
        if (e.code() == null || !e.code().matches("v[1-9][0-9]*") || !STATUSES.contains(e.status())
                || e.basePath() == null || !e.basePath().equals("/public-api/" + e.code())) {
            throw new IllegalStateException("Versão inválida em publicapi/versions.json: " + e.code());
        }
        if (e.status().equals("DEPRECATED") && (e.deprecated() == null || e.sunset() == null
                || !e.sunset().isAfter(e.deprecated()))) {
            throw new IllegalStateException("Versão depreciada exige deprecated e sunset (posterior): " + e.code());
        }
        return new ApiVersionView(e.code(), e.status(), e.basePath(), e.releasedOn(), e.deprecated(), e.sunset(),
                e.migrationGuide());
    }

    private static ChangelogRelease validated(ChangelogRelease r) {
        if (r.version() == null || !r.version().matches("\\d+\\.\\d+\\.\\d+") || r.date() == null || r.entries() == null) {
            throw new IllegalStateException("Release inválido em publicapi/changelog.json: " + r.version());
        }
        for (ChangelogEntry e : r.entries()) {
            if (!TYPES.contains(e.type()) || e.description() == null || e.description().isBlank()) {
                throw new IllegalStateException("Entrada inválida no changelog " + r.version());
            }
        }
        return r;
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler " + path, e);
        }
    }

    @Override
    public List<ApiVersionView> versions() {
        return versions;
    }

    @Override
    public List<ChangelogRelease> changelog() {
        return changelog;
    }
}
