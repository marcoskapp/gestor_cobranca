package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases;
import br.com.gestorcobranca.portal.domain.model.KnowledgeChallenge;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.service.AccessSecrets;
import br.com.gestorcobranca.shared.domain.DomainException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Confere o dado de conhecimento informado pelo devedor (nunca o devolve): data de nascimento ou os 4
 * últimos caracteres alfanuméricos do número de um contrato de origem (ou, sem contrato, do próprio
 * título) em cobrança no portal. Dado ausente no cadastro = verificação falha (a operação escolhe um
 * desafio que os dados da carteira sustentem).
 */
@Component
class KnowledgeVerifier {

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private final PortalDebts debts;
    private final OriginContractUseCases contracts;

    KnowledgeVerifier(PortalDebts debts, OriginContractUseCases contracts) {
        this.debts = debts;
        this.contracts = contracts;
    }

    boolean matches(KnowledgeChallenge type, PortalConfiguration config, UUID debtorId, String answer) {
        if (type == KnowledgeChallenge.NONE) {
            return true;
        }
        if (debtorId == null || answer == null || answer.isBlank() || answer.length() > 40) {
            return false;
        }
        return switch (type) {
            case BIRTH_DATE -> parseDate(answer).map(d -> d.equals(debts.debtor(debtorId).birthDate())).orElse(false);
            case CONTRACT_SUFFIX -> contractSuffixes(config, debtorId).stream()
                    .anyMatch(s -> AccessSecrets.constantTimeEquals(AccessSecrets.sha256(s),
                            AccessSecrets.sha256(suffix(answer))));
            case NONE -> true;
        };
    }

    private Set<String> contractSuffixes(PortalConfiguration config, UUID debtorId) {
        Set<String> suffixes = new LinkedHashSet<>();
        for (TitleView t : debts.titlesInPortal(config, debtorId)) {
            String reference = t.externalId();
            if (t.originContractId() != null) {
                try {
                    reference = contracts.get(t.originContractId()).externalId();
                } catch (DomainException e) {
                    // contrato indisponível: vale o número do título
                }
            }
            String s = suffix(reference);
            if (s.length() == 4) {
                suffixes.add(s);
            }
        }
        return suffixes;
    }

    /** Quatro últimos caracteres alfanuméricos, em maiúsculas. */
    static String suffix(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return clean.length() <= 4 ? clean : clean.substring(clean.length() - 4);
    }

    /** Aceita {@code dd/MM/aaaa} e {@code aaaa-MM-dd}. */
    static Optional<LocalDate> parseDate(String value) {
        String v = value.trim();
        try {
            return Optional.of(v.contains("/") ? LocalDate.parse(v, BR_DATE) : LocalDate.parse(v));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
