package br.com.gestorcobranca.incentive.web;

import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.CriterionInput;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.EvaluationCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.EvaluationView;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.FormCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.FormView;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.OccurrenceCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.OccurrenceView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Monitoria de qualidade (formulários, avaliações 0–10) e ocorrências de conduta. */
@RestController
@RequestMapping(ApiPaths.V1 + "/incentive/quality")
@Tag(name = "Incentive — qualidade", description = "Monitoria de qualidade e ocorrências de conduta")
class QualityController {

    private static final String VIEW = "hasAnyAuthority('PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', "
            + "'PERFORMANCE_VIEW_ALL', 'QUALITY_EVALUATE')";

    private final QualityUseCases quality;

    QualityController(QualityUseCases quality) {
        this.quality = quality;
    }

    @GetMapping("/forms")
    @PreAuthorize(VIEW)
    List<FormView> forms() {
        return quality.forms();
    }

    @PostMapping("/forms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('QUALITY_EVALUATE')")
    FormView createForm(@Valid @RequestBody FormRequest request) {
        return quality.createForm(new FormCommand(request.name(), request.description(), request.criteria().stream()
                .map(c -> new CriterionInput(c.code(), c.description(), c.weight(), c.critical())).toList()));
    }

    @PostMapping("/forms/{id}/deactivate")
    @PreAuthorize("hasAuthority('QUALITY_EVALUATE')")
    FormView deactivate(@PathVariable UUID id) {
        return quality.deactivateForm(id);
    }

    @PostMapping("/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('QUALITY_EVALUATE')")
    EvaluationView evaluate(@Valid @RequestBody EvaluationRequest request) {
        return quality.evaluate(new EvaluationCommand(request.formId(), request.userId(), request.referenceType(),
                request.referenceId(), request.evaluatedOn(), request.scores(), request.comments()));
    }

    @GetMapping("/evaluations")
    @PreAuthorize(VIEW)
    PageResponse<EvaluationView> evaluations(@RequestParam(required = false) UUID userId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return quality.evaluations(userId, from, to, PageQuery.of(page, size));
    }

    @PostMapping("/occurrences")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('QUALITY_EVALUATE')")
    OccurrenceView register(@Valid @RequestBody OccurrenceRequest request) {
        return quality.registerOccurrence(new OccurrenceCommand(request.userId(), request.type(), request.occurredOn(),
                request.description(), request.referenceType(), request.referenceId()));
    }

    @PostMapping("/occurrences/{id}/dismiss")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_MANAGE')")
    OccurrenceView dismiss(@PathVariable UUID id, @Valid @RequestBody DismissRequest request) {
        return quality.dismissOccurrence(id, request.reason());
    }

    @GetMapping("/occurrences")
    @PreAuthorize(VIEW)
    PageResponse<OccurrenceView> occurrences(@RequestParam(required = false) UUID userId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return quality.occurrences(userId, from, to, PageQuery.of(page, size));
    }

    record CriterionRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String code,
                            @NotBlank @Size(max = 300) String description,
                            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal weight,
                            boolean critical) {
    }

    record FormRequest(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description,
                       @NotEmpty @Size(max = 50) List<@Valid CriterionRequest> criteria) {
    }

    record EvaluationRequest(@NotNull UUID formId, @NotNull UUID userId,
                             @NotBlank @Pattern(regexp = "CALL|CONVERSATION|CASE|AGREEMENT|OTHER") String referenceType,
                             UUID referenceId, @NotNull LocalDate evaluatedOn,
                             @NotEmpty Map<@NotBlank String, @NotNull @DecimalMin("0") @DecimalMax("10") BigDecimal> scores,
                             @Size(max = 4000) String comments) {
    }

    record OccurrenceRequest(@NotNull UUID userId,
                             @NotBlank @Pattern(regexp = "SEVERE_COMPLIANCE_VIOLATION|COMPLIANCE_VIOLATION|COMPLAINT|UPHELD_DISPUTE")
                             String type,
                             @NotNull LocalDate occurredOn, @NotBlank @Size(min = 10, max = 2000) String description,
                             @Pattern(regexp = "CALL|CONVERSATION|CASE|AGREEMENT|OTHER") String referenceType,
                             UUID referenceId) {
    }

    record DismissRequest(@NotBlank @Size(min = 10, max = 2000) String reason) {
    }
}
