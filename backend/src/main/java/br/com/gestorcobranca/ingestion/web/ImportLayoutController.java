package br.com.gestorcobranca.ingestion.web;

import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.CanonicalLayoutView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.ColumnCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.ImportLayoutView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.PublishLayoutCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Layouts de importação por credor/carteira e o layout canônico (com modelo para download). */
@RestController
@RequestMapping(ApiPaths.V1 + "/ingestion/layouts")
class ImportLayoutController {

    private final ImportLayoutUseCases layouts;

    ImportLayoutController(ImportLayoutUseCases layouts) {
        this.layouts = layouts;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    List<ImportLayoutView> list(@RequestParam(required = false) UUID creditorId,
                                @RequestParam(defaultValue = "false") boolean includeInactive) {
        return layouts.list(creditorId, includeInactive);
    }

    @GetMapping("/standard")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    CanonicalLayoutView standard() {
        return layouts.canonicalLayout();
    }

    /** Modelo vazio do layout canônico ({@code format} = csv ou xlsx). */
    @GetMapping("/standard/template")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    void template(@RequestParam(defaultValue = "csv") String format, HttpServletResponse response) throws IOException {
        String extension = format.strip().toLowerCase(Locale.ROOT);
        boolean xlsx = "xlsx".equals(extension);
        response.setContentType(xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("modelo-importacao." + (xlsx ? "xlsx" : "csv")).build().toString());
        layouts.writeTemplate(xlsx ? "XLSX" : "CSV", response.getOutputStream());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    ImportLayoutView get(@PathVariable UUID id) {
        return layouts.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ImportLayoutView publish(@Valid @RequestBody PublishLayoutRequest request) {
        return layouts.publish(new PublishLayoutCommand(request.creditorId(), request.portfolioId(), request.code(),
                request.name(), request.format(), request.delimiter(), request.encoding(), request.hasHeader(),
                request.sheetName(), request.columns().stream()
                .map(c -> new ColumnCommand(c.field(), c.header(), c.position())).toList()));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ImportLayoutView deactivate(@PathVariable UUID id) {
        return layouts.deactivate(id);
    }

    record PublishLayoutRequest(@NotNull UUID creditorId, UUID portfolioId, @NotBlank @Size(max = 40) String code,
                                @NotBlank @Size(max = 150) String name, @NotBlank @Size(max = 10) String format,
                                @Size(max = 3) String delimiter, @Size(max = 20) String encoding, boolean hasHeader,
                                @Size(max = 31) String sheetName,
                                @NotEmpty @Size(max = 60) List<@Valid ColumnRequest> columns) {
    }

    record ColumnRequest(@NotBlank @Size(max = 40) String field, @Size(max = 120) String header,
                         @Positive Integer position) {
    }
}
