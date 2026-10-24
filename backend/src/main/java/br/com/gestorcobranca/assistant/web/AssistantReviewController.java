package br.com.gestorcobranca.assistant.web;

import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases.QualityQuery;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases.QualityView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases.ReviewCommand;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases.ReviewView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases;
import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases.SessionView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases.TurnDetail;
import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases.TurnView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Trilha dos turnos, revisão por amostragem e métricas de qualidade do assistente. */
@RestController
@RequestMapping(ApiPaths.V1 + "/assistant")
@Tag(name = "Assistant — trilha e avaliação", description = "Turnos do assistente, revisão humana e qualidade")
class AssistantReviewController {

    private static final String REVIEW = "hasAuthority('ASSISTANT_REVIEW')";

    private final AssistantTrailUseCases trail;
    private final AssistantReviewUseCases reviews;

    AssistantReviewController(AssistantTrailUseCases trail, AssistantReviewUseCases reviews) {
        this.trail = trail;
        this.reviews = reviews;
    }

    @GetMapping("/conversations/{conversationId}/session")
    @PreAuthorize(REVIEW)
    ResponseEntity<SessionView> session(@PathVariable UUID conversationId) {
        return trail.session(conversationId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/conversations/{conversationId}/turns")
    @PreAuthorize(REVIEW)
    PageResponse<TurnView> turns(@PathVariable UUID conversationId, @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        return trail.turns(conversationId, PageQuery.of(page, size));
    }

    @GetMapping("/turns/{turnId}")
    @PreAuthorize(REVIEW)
    TurnDetail turn(@PathVariable UUID turnId) {
        return trail.turn(turnId);
    }

    @GetMapping("/reviews")
    @PreAuthorize(REVIEW)
    PageResponse<ReviewView> queue(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) UUID creditorId,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        return reviews.queue(status, creditorId, PageQuery.of(page, size));
    }

    @PostMapping("/reviews/{id}")
    @PreAuthorize(REVIEW)
    ReviewView submit(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return reviews.submit(id, new ReviewCommand(request.score(), request.verdict(), request.tags(), request.comment()));
    }

    @GetMapping("/quality")
    @PreAuthorize(REVIEW)
    QualityView quality(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) UUID creditorId,
                        @RequestParam(required = false) UUID portfolioId) {
        return reviews.quality(new QualityQuery(from, to, creditorId, portfolioId));
    }

    record ReviewRequest(@Min(1) @Max(5) int score, @NotBlank String verdict, @Size(max = 8) List<String> tags,
                         @Size(max = 2000) String comment) {
    }
}
