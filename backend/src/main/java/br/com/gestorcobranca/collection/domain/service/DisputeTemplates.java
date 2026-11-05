package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DisputeOutcome;
import br.com.gestorcobranca.collection.domain.model.DisputeTemplateKind;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modelos de e-mail das contestações (regra pura): modelos padrão do sistema, variáveis permitidas por tipo de
 * modelo e preenchimento. Pedido ao credor: um modelo por tipo de contestação, pedindo o documento que o padrão de
 * mercado exige para cada alegação. Resposta ao devedor: um modelo por resultado, com a fundamentação (parecer).
 * <p>
 * Minimização (LGPD): o pedido ao credor identifica a dívida pelos títulos (número no credor, parcela, vencimento,
 * valores) e pelo protocolo — nenhuma variável traz nome, documento ou contato do devedor. A resposta ao devedor
 * usa só o primeiro nome.
 */
public final class DisputeTemplates {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");

    /** Variáveis do pedido ao credor. */
    public static final List<String> CREDITOR_VARIABLES = List.of("credor", "assessoria", "protocolo", "tipo",
            "titulos", "valor_contestado", "prazo_credor", "data_abertura", "responsavel", "link_evidencias",
            "validade_link");

    /** Variáveis da resposta ao devedor. */
    public static final List<String> DEBTOR_VARIABLES = List.of("primeiro_nome", "credor", "assessoria", "protocolo",
            "tipo", "titulos", "data_abertura", "resultado", "fundamentacao", "responsavel");

    private DisputeTemplates() {
    }

    public record Text(String subject, String body) {
    }

    /** Chaves válidas por tipo de modelo: tipo de contestação (credor) ou resultado (devedor). */
    public static List<String> keys(DisputeTemplateKind kind) {
        return kind == DisputeTemplateKind.CREDITOR_REQUEST
                ? Arrays.stream(DisputeType.values()).map(Enum::name).toList()
                : Arrays.stream(DisputeOutcome.values()).map(Enum::name).toList();
    }

    public static List<String> variables(DisputeTemplateKind kind) {
        return kind == DisputeTemplateKind.CREDITOR_REQUEST ? CREDITOR_VARIABLES : DEBTOR_VARIABLES;
    }

    /** Recusa variável desconhecida no assunto ou no texto ({@code dispute_template.variable_unknown}). */
    public static void validate(DisputeTemplateKind kind, String key, String subject, String body) {
        if (!keys(kind).contains(key)) {
            throw new BusinessRuleException("dispute_template.key_invalid", "Modelo inexistente: " + key);
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (String text : new String[]{subject, body}) {
            Matcher m = VARIABLE.matcher(text == null ? "" : text);
            while (m.find()) {
                if (!variables(kind).contains(m.group(1))) {
                    unknown.add(m.group(1));
                }
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("dispute_template.variable_unknown",
                    "Variável desconhecida no modelo: " + String.join(", ", unknown));
        }
    }

    /** Preenche as variáveis; variável sem valor fica vazia. */
    public static String fill(String text, Map<String, String> values) {
        Matcher m = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** O texto final (já editado pelo analista) não pode conter variável sem preencher. */
    public static boolean hasPlaceholders(String text) {
        return text != null && VARIABLE.matcher(text).find();
    }

    public static Text defaultFor(DisputeTemplateKind kind, String key) {
        return kind == DisputeTemplateKind.CREDITOR_REQUEST ? creditorDefault(DisputeType.valueOf(key))
                : debtorDefault(DisputeOutcome.valueOf(key));
    }

    private static final String CREDITOR_HEADER = """
            Prezados(as), equipe de contestações {{credor}},

            Recebemos uma contestação de dívida (protocolo {{protocolo}}, motivo: {{tipo}}, aberta em {{data_abertura}}) \
            referente aos títulos abaixo, que estão com a cobrança suspensa até a conclusão da análise:

            {{titulos}}
            Valor contestado: {{valor_contestado}}

            """;

    private static final String CREDITOR_FOOTER = """

            Pedimos o retorno até {{prazo_credor}}, respondendo a este e-mail. Documentos de apoio: {{link_evidencias}}
            (link de acesso restrito, válido até {{validade_link}}).

            Por se tratar de dados pessoais, envie somente o necessário para a análise desta contestação.

            Atenciosamente,
            {{responsavel}}
            {{assessoria}}
            """;

    private static Text creditorDefault(DisputeType type) {
        String ask = switch (type) {
            case NOT_RECOGNIZED -> """
                    O devedor não reconhece a dívida. Para a análise, solicitamos:
                    - contrato, termo de adesão ou pedido assinado/aceito pelo devedor;
                    - comprovante da origem da dívida (nota fiscal, fatura, extrato do produto contratado);
                    - data e canal da contratação.""";
            case ALREADY_PAID -> """
                    O devedor afirma que já pagou. Para a análise, solicitamos:
                    - confirmação se há pagamento ou baixa registrada no seu sistema para os títulos;
                    - comprovante da baixa ou extrato de pagamentos dos títulos, com datas e valores;
                    - se não houver registro, a confirmação de que a dívida permanece em aberto.""";
            case WRONG_AMOUNT -> """
                    O devedor contesta o valor cobrado. Para a análise, solicitamos:
                    - memória de cálculo dos títulos (principal, juros, multa, encargos, índices e datas usados);
                    - contrato ou condições com as taxas pactuadas;
                    - pagamentos parciais já recebidos, se houver.""";
            case FRAUD -> """
                    O devedor alega fraude (contratação por terceiro). Para a análise, solicitamos:
                    - contrato assinado e documentos apresentados na contratação;
                    - registro da contratação (canal, data, local ou endereço de acesso, validação de identidade);
                    - informação sobre ocorrência de fraude já registrada para este cliente.""";
            case PRESCRIBED -> """
                    O devedor alega prescrição. Para a análise, solicitamos:
                    - data de vencimento original e data de constituição da dívida;
                    - eventos que interromperam a prescrição (protesto, ação judicial, reconhecimento da dívida), com datas;
                    - confirmação de que os títulos podem seguir em cobrança.""";
            case PRODUCT_OR_SERVICE -> """
                    O devedor alega que o produto ou serviço não foi entregue ou apresentou defeito. Para a análise, solicitamos:
                    - comprovante de entrega ou de prestação do serviço (canhoto, rastreio, ordem de serviço);
                    - registros de reclamação, troca, cancelamento ou assistência técnica;
                    - posição do credor sobre a manutenção da cobrança.""";
            case OTHER -> """
                    Para a análise da contestação, solicitamos os documentos e esclarecimentos que comprovem a origem e o
                    valor da dívida e a posição do credor sobre a manutenção da cobrança.""";
        };
        return new Text("Contestação {{protocolo}} — pedido de documentos ({{tipo}})",
                CREDITOR_HEADER + ask + "\n" + CREDITOR_FOOTER);
    }

    private static Text debtorDefault(DisputeOutcome outcome) {
        String result = switch (outcome) {
            case UPHELD -> """
                    Após a análise, sua contestação foi considerada PROCEDENTE. A cobrança dos títulos contestados foi \
                    encerrada e os ajustes necessários serão feitos junto ao credor {{credor}}.""";
            case PARTIALLY_UPHELD -> """
                    Após a análise, sua contestação foi considerada PARCIALMENTE PROCEDENTE. Os valores serão ajustados \
                    junto ao credor {{credor}} e a cobrança seguirá apenas sobre o que permanece devido.""";
            case REJECTED -> """
                    Após a análise, sua contestação foi considerada IMPROCEDENTE: a dívida foi confirmada pelos documentos \
                    analisados e a cobrança dos títulos será retomada.""";
        };
        String body = """
                Olá, {{primeiro_nome}}.

                Concluímos a análise da sua contestação (protocolo {{protocolo}}, aberta em {{data_abertura}}, motivo: \
                {{tipo}}) sobre os títulos abaixo, do credor {{credor}}:

                {{titulos}}
                """ + result + """


                Fundamentação:
                {{fundamentacao}}

                Se tiver dúvidas ou novos documentos, responda a esta mensagem informando o protocolo.

                Atenciosamente,
                {{responsavel}}
                {{assessoria}}
                """;
        return new Text("Resultado da sua contestação — protocolo {{protocolo}}", body);
    }
}
