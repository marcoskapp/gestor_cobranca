package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceFilter;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.ResolveCommand;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementImportView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementLineView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementUpload;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/** Conciliação: importação de extratos/arquivos de retorno e fila de divergências. */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/reconciliation")
@PreAuthorize("hasAuthority('RECONCILIATION_MANAGE')")
class ReconciliationController {

    private final ReconciliationUseCases reconciliation;

    ReconciliationController(ReconciliationUseCases reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping(path = "/statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    StatementImportView importStatement(@RequestParam(defaultValue = "CSV_GENERIC") String format,
                                        @RequestParam(required = false) UUID creditorId,
                                        @RequestPart("file") MultipartFile file) {
        try {
            return reconciliation.importStatement(new StatementUpload(format, creditorId, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    @GetMapping("/statements")
    PageResponse<StatementImportView> imports(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return reconciliation.imports(PageQuery.of(page, size));
    }

    @GetMapping("/statements/{id}")
    StatementImportView getImport(@PathVariable UUID id) {
        return reconciliation.getImport(id);
    }

    @GetMapping("/statements/{id}/lines")
    PageResponse<StatementLineView> lines(@PathVariable UUID id, @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return reconciliation.lines(id, PageQuery.of(page, size));
    }

    @GetMapping("/divergences")
    PageResponse<DivergenceView> divergences(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return reconciliation.divergences(new DivergenceFilter(status, type), PageQuery.of(page, size));
    }

    @GetMapping("/divergences/{id}")
    DivergenceView divergence(@PathVariable UUID id) {
        return reconciliation.getDivergence(id);
    }

    @PostMapping("/divergences/{id}/resolve")
    DivergenceView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
        return reconciliation.resolve(id, new ResolveCommand(request.resolution(), request.paymentId(), request.note()));
    }

    record ResolveRequest(@NotBlank @Size(max = 30) String resolution, UUID paymentId,
                          @NotBlank @Size(max = 1000) String note) {
    }
}
