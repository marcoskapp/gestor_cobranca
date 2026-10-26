package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.out.CampaignOfferPort.OfferResult;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.campaign.domain.model.VariableSource;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases;
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
 * do cadastro (primeiro nome do devedor, nome do credor), da oferta, da próxima parcela do acordo e do
 * link do portal gerado no envio. Os valores seguem os formatos do communication (valor
 * {@code 1234.56}, data ISO), que os valida e guarda criptografados; nada é gravado nem registrado em
 * log aqui (o link do portal é segredo de acesso).
 */
@Component
class VariableResolver {

    private final DebtorUseCases debtors;
    private final CreditorUseCases creditors;

    VariableResolver(DebtorUseCases debtors, CreditorUseCases creditors) {
        this.debtors = debtors;
        this.creditors = creditors;
    }

    /**
     * Dados obtidos no envio, além da posição do caso (cada um nulo quando a etapa não usa a origem).
     *
     * @param installment próxima parcela em aberto do acordo em vigor do caso
     * @param portalLink  link do portal gerado para esta mensagem — nunca registrar
     */
    record Inputs(OfferResult offer, AgreementInstallments.NextInstallment installment, PortalLinkUseCases.LinkIssue portalLink) {

        static final Inputs NONE = new Inputs(null, null, null);
    }

    Map<String, String> resolve(Map<String, String> spec, UUID debtorId, AudienceSnapshot s, Inputs inputs, Instant now) {
        Map<String, String> values = new LinkedHashMap<>();
        Inputs in = inputs == null ? Inputs.NONE : inputs;
        spec.forEach((name, source) -> values.put(name, value(StepAction.source(source), source, debtorId, s, in, now)));
        return values;
    }

    private String value(VariableSource source, String spec, UUID debtorId, AudienceSnapshot s, Inputs in,
                         Instant now) {
        OfferResult offer = in.offer();
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
            case NEXT_INSTALLMENT_AMOUNT -> in.installment() == null ? throwUnavailable(source)
                    : money(in.installment().amount());
            case NEXT_INSTALLMENT_DUE_DATE -> in.installment() == null || in.installment().dueDate() == null
                    ? throwUnavailable(source) : in.installment().dueDate().toString();
            case PORTAL_LINK -> in.portalLink() == null || !in.portalLink().available() ? throwUnavailable(source)
                    : in.portalLink().url();
            case PORTAL_LINK_HOURS -> in.portalLink() == null || !in.portalLink().available() ? throwUnavailable(source)
                    : String.valueOf(in.portalLink().validityHours());
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
