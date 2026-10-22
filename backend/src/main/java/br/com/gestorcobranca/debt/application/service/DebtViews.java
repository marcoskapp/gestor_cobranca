package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.shared.config.TimeConfig;

import java.time.Clock;
import java.time.LocalDate;

/** Conversão de entidades em views públicas (dados pessoais sempre mascarados). */
final class DebtViews {

    private DebtViews() {
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    static DebtorView debtor(Debtor d) {
        return new DebtorView(d.getId(), d.getPersonType().name(), d.getName(), d.maskedDocument(), d.getBirthDate(),
                d.getStatus().name(), d.getNotes(), d.getCreatedAt(), d.getAnonymizedAt());
    }

    static ContactView contact(DebtorContact c) {
        return new ContactView(c.getId(), c.getDebtorId(), c.getType().name(), c.maskedValue(), c.getLabel().name(),
                c.isWhatsapp(), c.getSource().name(), c.getStatus().name(), c.getPriority(), c.getLastVerifiedAt(),
                c.getInvalidReason());
    }

    static TitleView title(DebtTitle t, LocalDate today) {
        return new TitleView(t.getId(), t.getCreditorId(), t.getPortfolioId(), t.getDebtorId(), t.getOriginContractId(),
                t.getExternalId(), t.getInstallmentNumber(), t.getDueDate(), t.getPrincipalAmount().amount(),
                t.getOpenBalance().amount(), t.getPaidTotal().amount(), t.getStatus().name(), t.getStatusReason(),
                t.isDisputeBlocked(), t.getDisputeId(), t.getLastPaymentAt(), t.daysOverdue(today), t.getImportBatchId());
    }

    /**
     * Nome reduzido às iniciais para a trilha de auditoria. A auditoria é imutável e precisa
     * continuar sem dados pessoais legíveis depois de uma anonimização (LGPD).
     */
    static String maskName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder masked = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                masked.append(masked.isEmpty() ? "" : " ").append(part.charAt(0)).append("***");
            }
        }
        return masked.toString();
    }

    /** Data de nascimento na auditoria: apenas a indicação de preenchimento. */
    static String maskDate(LocalDate date) {
        return date == null ? null : "****-**-**";
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String errorCode, String label) {
        if (value == null || value.isBlank()) {
            throw new br.com.gestorcobranca.shared.domain.BusinessRuleException(errorCode, label + " obrigatório");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new br.com.gestorcobranca.shared.domain.BusinessRuleException(errorCode, label + " inválido");
        }
    }
}
