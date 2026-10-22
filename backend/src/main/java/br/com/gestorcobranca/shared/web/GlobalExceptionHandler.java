package br.com.gestorcobranca.shared.web;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Tradução de exceções para respostas RFC 9457 (Problem Details). Todo erro inclui
 * {@code code} (estável, para o frontend) e {@code requestId} (para suporte localizar o log).
 * <p>
 * Erros 5xx nunca expõem detalhes internos ao cliente; o detalhe completo vai para o log
 * técnico com o mesmo requestId.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI TYPE_BASE = URI.create("https://gestorcobranca.com.br/problems/");

    private final AccessLogger accessLogger;
    private final CurrentActor currentActor;
    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(AccessLogger accessLogger, CurrentActor currentActor, MeterRegistry meterRegistry) {
        this.accessLogger = accessLogger;
        this.currentActor = currentActor;
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException e) {
        return domainProblem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        LOG.atInfo().addKeyValue("error.code", e.code()).log("Regra de negócio violada: {}", e.getMessage());
        return domainProblem(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        LOG.atInfo().addKeyValue("error.code", e.code()).log("Conflito de estado: {}", e.getMessage());
        return domainProblem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        return domainProblem(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    @ExceptionHandler(br.com.gestorcobranca.shared.security.AuthenticationFailedException.class)
    ProblemDetail authenticationFailed(br.com.gestorcobranca.shared.security.AuthenticationFailedException e) {
        // o registro no log de acesso é feito pelo fluxo de autenticação, com o motivo real
        return problem(HttpStatus.UNAUTHORIZED, e.code(), e.getMessage());
    }

    @ExceptionHandler(ScopeAccessDeniedException.class)
    ProblemDetail scopeDenied(ScopeAccessDeniedException e) {
        // o registro no log de acesso já foi feito por AccessScope
        return problem(HttpStatus.FORBIDDEN, "access.denied", "Acesso negado");
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ProblemDetail accessDenied(RuntimeException e) {
        currentActor.find().ifPresent(actor -> accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor,
                Map.of("reason", "missing_permission"))));
        return problem(HttpStatus.FORBIDDEN, "access.denied", "Acesso negado");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation.failed", "Dados inválidos");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraint(ConstraintViolationException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation.failed", "Dados inválidos");
        problem.setProperty("errors", e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList());
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "request.malformed", "Requisição malformada");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "upload.too_large", "Arquivo excede o tamanho máximo permitido");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(ObjectOptimisticLockingFailureException e) {
        meterRegistry.counter("errors.optimistic_lock").increment();
        LOG.warn("Alteração concorrente detectada: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, "concurrent_modification",
                "O registro foi alterado por outra operação. Recarregue e tente novamente.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        meterRegistry.counter("errors.data_integrity").increment();
        LOG.warn("Violação de integridade de dados", e);
        return problem(HttpStatus.CONFLICT, "data_integrity", "Operação conflita com dados existentes");
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ProblemDetail responseStatus(org.springframework.web.server.ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String code = status == HttpStatus.TOO_MANY_REQUESTS ? "rate_limited" : "http." + status.value();
        return problem(status, code, e.getReason() == null ? status.getReasonPhrase() : e.getReason());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noResource(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "route.not_found", "Recurso não encontrado");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        meterRegistry.counter("errors.unexpected", "type", e.getClass().getSimpleName()).increment();
        LOG.error("Erro inesperado", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Erro interno. Informe o código de requisição ao suporte.");
    }

    private ProblemDetail domainProblem(HttpStatus status, DomainException e) {
        ProblemDetail problem = problem(status, e.code(), e.getMessage());
        if (!e.details().isEmpty()) {
            problem.setProperty("details", e.details());
        }
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.replace('.', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        return problem;
    }
}
