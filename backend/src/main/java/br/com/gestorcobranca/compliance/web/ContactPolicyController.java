package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.EffectivePolicyView;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PolicyView;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
import br.com.gestorcobranca.compliance.application.port.in.HolidayUseCases;
import br.com.gestorcobranca.compliance.application.port.in.HolidayUseCases.HolidayView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Políticas de contato versionadas e calendário de feriados. */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance")
class ContactPolicyController {

    private final ContactPolicyUseCases policies;
    private final HolidayUseCases holidays;

    ContactPolicyController(ContactPolicyUseCases policies, HolidayUseCases holidays) {
        this.policies = policies;
        this.holidays = holidays;
    }

    @PostMapping("/contact-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    PolicyView publish(@Valid @RequestBody PublishPolicyRequest request) {
        return policies.publish(new PublishPolicyCommand(request.creditorId(), request.effectiveFrom(), request.notes(),
                request.rules().stream().map(RuleRequest::toInput).toList()));
    }

    /** Regras efetivas agora; {@code creditorId} omitido = padrão da assessoria. */
    @GetMapping("/contact-policies/effective")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    EffectivePolicyView effective(@RequestParam(required = false) UUID creditorId) {
        return policies.effective(creditorId);
    }

    @GetMapping("/contact-policies")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    List<PolicyView> history(@RequestParam(required = false) UUID creditorId) {
        return policies.history(creditorId);
    }

    @GetMapping("/holidays")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    List<HolidayView> listHolidays(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return holidays.list(from, to);
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    HolidayView addHoliday(@Valid @RequestBody HolidayRequest request) {
        return holidays.add(request.date(), request.name());
    }

    @DeleteMapping("/holidays/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    void removeHoliday(@PathVariable UUID id) {
        holidays.remove(id);
    }

    record PublishPolicyRequest(UUID creditorId, Instant effectiveFrom, @Size(max = 1000) String notes,
                                @NotEmpty @Size(max = 5) List<@Valid RuleRequest> rules) {
    }

    record RuleRequest(@NotBlank @Size(max = 20) String channel, LocalTime windowStart, LocalTime windowEnd,
                       @Size(max = 7) List<@NotBlank String> allowedDays,
                       @Min(1) @Max(1000) Integer maxPerDay, @Min(1) @Max(1000) Integer maxPerWeek,
                       @Min(1) @Max(43200) Integer minIntervalMinutes, boolean blockOnHolidays) {

        ChannelRuleInput toInput() {
            return new ChannelRuleInput(channel, windowStart, windowEnd, allowedDays, maxPerDay, maxPerWeek,
                    minIntervalMinutes, blockOnHolidays);
        }
    }

    record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 120) String name) {
    }
}
