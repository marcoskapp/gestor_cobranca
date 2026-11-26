package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Item;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Texto pronto do aviso ao credor (decisão 1479), para a pessoa copiar e enviar pelo canal da assessoria. Identifica o
 * titular só pelos dados mínimos que o credor já tem — a referência dos títulos dele, com parcela e carteira — e nunca
 * por nome, documento ou contato (o sistema não os tem mais e o aviso não pode reexpô-los). Pede ao credor a
 * eliminação no sistema dele e que as próximas cargas venham sem as linhas destes títulos. Com vários titulares (remessa
 * por credor, decisão 1484), um bloco por titular, numerado só na ordem do texto.
 */
public final class CreditorErasureNoticeText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private CreditorErasureNoticeText() {
    }

    /** Titular citado no aviso: data da anonimização (fuso da operação) e títulos do credor. */
    public record Subject(LocalDate erasedOn, List<Item> items) {
    }

    /**
     * @param creditorName   nome do credor como cadastrado (fantasia ou razão social)
     * @param portfolioNames nome de cada carteira citada (ausente = "carteira não identificada")
     * @param erasedOn       data da anonimização no fuso da operação
     */
    public static String compose(String creditorName, Map<UUID, String> portfolioNames, LocalDate erasedOn,
                                 List<Item> items) {
        return compose(creditorName, portfolioNames, List.of(new Subject(erasedOn, items)));
    }

    /** Um ou vários titulares do mesmo credor. */
    public static String compose(String creditorName, Map<UUID, String> portfolioNames, List<Subject> subjects) {
        boolean single = subjects.size() == 1;
        StringBuilder text = new StringBuilder()
                .append(single ? "Assunto: Eliminação de dados de titular (LGPD, art. 18, § 6º)\n\n"
                        : "Assunto: Eliminação de dados de titulares (LGPD, art. 18, § 6º)\n\n")
                .append("Prezados(as), ").append(creditorName).append(",\n\n");
        if (single) {
            text.append("Em ").append(DATE.format(subjects.getFirst().erasedOn()))
                    .append(", eliminamos (anonimizamos) em nosso sistema os dados pessoais do titular vinculado aos ")
                    .append("títulos abaixo, em atendimento à Lei Geral de Proteção de Dados. Os títulos continuam ")
                    .append("registrados, sem dados que identifiquem a pessoa.\n\n");
            appendTitles(text, portfolioNames, subjects.getFirst().items());
        } else {
            text.append("Eliminamos (anonimizamos) em nosso sistema os dados pessoais de ").append(subjects.size())
                    .append(" titulares vinculados aos títulos abaixo, em atendimento à Lei Geral de Proteção de ")
                    .append("Dados. Os títulos continuam registrados, sem dados que identifiquem as pessoas.\n");
            for (int n = 0; n < subjects.size(); n++) {
                Subject subject = subjects.get(n);
                text.append("\nTitular ").append(n + 1).append(" (dados eliminados em ")
                        .append(DATE.format(subject.erasedOn())).append("):\n");
                appendTitles(text, portfolioNames, subject.items());
            }
        }
        boolean coObligor = subjects.stream().flatMap(subject -> subject.items().stream())
                .anyMatch(Item::isCoObligorContract);
        text.append("\nPedimos que:\n")
                .append(single ? "1. eliminem ou anonimizem este titular" : "1. eliminem ou anonimizem estes titulares")
                .append(" também no sistema de vocês, como determina a LGPD (art. 18, § 6º);\n")
                .append("2. não incluam as linhas destes títulos nos próximos arquivos de carga: o arquivo que as ")
                .append("trouxer terá essas linhas recusadas e precisará ser enviado de novo sem elas");
        if (coObligor) {
            // decisão 1605: o credor do contrato em que o titular era coobrigado também é avisado
            text.append(";\n3. nos contratos marcados como coobrigação, não informem mais ")
                    .append(single ? "esta pessoa" : "estas pessoas").append(" como coobrigada");
        }
        return text.append(".\n\n")
                .append("Por segurança, este aviso não traz nome, documento nem contato ")
                .append(single ? "do titular.\n" : "dos titulares.\n")
                .toString();
    }

    private static void appendTitles(StringBuilder text, Map<UUID, String> portfolioNames, List<Item> items) {
        Map<UUID, List<Item>> byPortfolio = new LinkedHashMap<>();
        items.forEach(i -> byPortfolio.computeIfAbsent(i.getPortfolioId(), k -> new ArrayList<>()).add(i));
        byPortfolio.forEach((portfolioId, list) -> {
            text.append("Carteira ").append(portfolioNames.getOrDefault(portfolioId, "não identificada")).append(":\n");
            list.forEach(i -> {
                text.append(i.isCoObligorContract() ? "- " : "- Título ").append(i.getReference());
                if (i.getInstallmentNumber() != null) {
                    text.append(", parcela ").append(i.getInstallmentNumber());
                }
                text.append('\n');
            });
        });
    }
}
