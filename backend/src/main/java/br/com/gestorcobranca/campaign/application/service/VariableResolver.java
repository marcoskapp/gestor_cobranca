package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.out.CampaignOfferPort.OfferResult;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.campaign.domain.model.VariableSource;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Valores das variáveis de template de uma etapa, calculados no envio a partir da posição do caso,
 * do cadastro (primeiro nome do devedor, nome do credor) e da oferta. Os valores seguem os formatos
 * do communication (valor {@code 1234.56}, data ISO), que os valida e guarda criptografados; nada é
 * gravado nem registrado em log aqui.
 */
@Component
class VariableResolver {

    private final DebtorUseCases debtors;
    private final CreditorUseCases creditors;

    VariableResolver(DebtorUseCases debtors, CreditorUseCases creditors) {
        this.debtors = debtors;
        this.creditors = creditors;
    }

    Map<String, String> resolve(Map<String, String> spec, UUID debtorId, AudienceSnapshot s, OfferResult offer,
                                Instant now) {
        Map<String, String> values = new LinkedHashMap<>();
        spec.forEach((name, source) -> values.put(name, value(StepAction.source(source), source, debtorId, s, offer, now)));
        return values;
    }

    private String value(VariableSource source, String spec, UUID debtorId, AudienceSnapshot s, OfferResult offer,
                         Instant now) {
        return switch (source) {
            case DEBTOR_FIRST_NAME -> firstName(debtors.get(debtorId).name());
            case OPEN_BALANCE -> money(s.openBalance());
            case ELIGIBLE_BALANCE -> money(s.eligibleBalance());
            case OLDEST_DUE_DATE -> {
                if (s.oldestDueDate() == null) {
                    throw unavailable(source);
                }
                yield s.oldestDueDate().toString();
            }
            case DAYS_OVERDUE -> String.valueOf(s.daysOverdue(now.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate()));
            case CREDITOR_NAME -> {
                var creditor = creditors.get(s.creditorId());
                yield creditor.tradeName() != null && !creditor.tradeName().isBlank() ? creditor.tradeName()
                        : creditor.legalName();
            }
            case OFFER_AMOUNT -> offer == null || offer.amount() == null ? throwUnavailable(source) : money(offer.amount());
            case OFFER_DUE_DATE -> offer == null || offer.dueDate() == null ? throwUnavailable(source)
                    : offer.dueDate().toString();
            case PAYMENT_LINK -> offer == null || offer.paymentUrl() == null ? throwUnavailable(source) : offer.paymentUrl();
            case LITERAL -> StepAction.literal(spec);
        };
    }

    /** Primeira palavra do nome, só com letras, apóstrofo e hífen (tipo FIRST_NAME do communication). */
    static String firstName(String fullName) {
        String first = fullName == null ? "" : fullName.trim().split("\\s+")[0].replaceAll("[^\\p{L}'-]", "");
        while (!first.isEmpty() && !Character.isLetter(first.charAt(0))) {
            first = first.substring(1);
        }
        if (first.isEmpty()) {
            throw unavailable(VariableSource.DEBTOR_FIRST_NAME);
        }
        return first.length() > 30 ? first.substring(0, 30) : first;
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String throwUnavailable(VariableSource source) {
        throw unavailable(source);
    }

    private static BusinessRuleException unavailable(VariableSource source) {
        return new BusinessRuleException("campaign.variable_unavailable",
                "Valor indisponível para a variável de origem " + source.name());
    }
}
