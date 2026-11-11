package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.LegalBasis;
import br.com.gestorcobranca.compliance.domain.model.ProcessorRole;
import br.com.gestorcobranca.compliance.domain.model.RequestType;
import br.com.gestorcobranca.compliance.domain.model.TransferSafeguard;
import br.com.gestorcobranca.shared.privacy.DataRecipientType;
import br.com.gestorcobranca.shared.privacy.DataSharingPurpose;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Monta o rascunho da resposta ao titular (LGPD art. 9º e 18, I e VII) a partir do que o sistema registra: credores,
 * prestadores (operadores), escritórios e integrações. O texto é em linguagem simples (sem códigos internos) e cada
 * parágrafo aponta os destinatários de onde veio — o analista confere, edita e só então usa (revisão humana).
 * Operadores cadastrados à mão (ex.: hospedagem) chegam como prestador sem finalidade do sistema: a linha usa a do
 * cadastro. A frase "não compartilhamos com outras empresas além das listadas" só sai com todas as fontes consultadas.
 * <p>
 * Alertas: prestador sem cadastro de operador ou com cadastro inativo/vencido, credor sem base legal vigente, fonte
 * indisponível (rascunho possivelmente incompleto) e texto acima do limite da resposta.
 */
public final class ResponseDraftComposer {

    /** Limite da resposta registrada na solicitação. */
    public static final int TEXT_MAX = 4000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private ResponseDraftComposer() {
    }

    /** Categoria de dado pessoal tratada, com o texto para o titular. */
    public enum DataCategory {
        IDENTIFICATION("identificação (nome e CPF/CNPJ)"),
        DEBT("informações das dívidas, acordos e pagamentos"),
        CONTACT("contatos (telefone e e-mail)"),
        MESSAGES("mensagens trocadas conosco"),
        CALLS("registro das ligações telefônicas"),
        PAYMENT("dados das cobranças Pix emitidas"),
        LEGAL("informações de cobrança judicial ou extrajudicial");

        private final String label;

        DataCategory(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Severity { WARNING, DANGER }

    /**
     * Cadastro do operador que casa com a chave do prestador.
     *
     * @param current ativo e com contrato vigente hoje
     */
    public record ProcessorInfo(String name, ProcessorRole role, String purpose, String countryCode,
                                TransferSafeguard safeguard, boolean current) {
    }

    /**
     * Destinatário agregado (fatos da mesma chave somados).
     *
     * @param legalBases bases legais vigentes (só credor; vazio = sem registro vigente)
     * @param processor  cadastro do operador (só prestador; nulo = sem cadastro)
     */
    public record Recipient(DataRecipientType type, String key, String name, Set<DataSharingPurpose> purposes,
                            List<LegalBasis> legalBases, ProcessorInfo processor) {

        public Recipient {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(key, "key");
            purposes = purposes == null || purposes.isEmpty() ? Set.of() : Set.copyOf(purposes);
            legalBases = legalBases == null ? List.of() : List.copyOf(legalBases);
        }

        /** Nome para o titular: o do cadastro do operador, se houver. */
        public String displayName() {
            return processor != null && processor.name() != null ? processor.name() : name;
        }
    }

    /**
     * @param requesterName      nome informado pelo requerente (o texto usa o primeiro nome)
     * @param subjectFound       titular localizado na base (devedor vinculado e não anonimizado)
     * @param unavailableSources áreas que não puderam ser consultadas
     */
    public record Input(RequestType type, String requesterName, LocalDate receivedOn, boolean subjectFound,
                        List<Recipient> recipients, List<String> unavailableSources) {

        public Input {
            Objects.requireNonNull(type, "type");
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
        }
    }

    /** Parágrafo do texto e as chaves dos destinatários que o originaram (explicabilidade). */
    public record Paragraph(String text, List<String> recipientKeys) {
    }

    public record Alert(String code, Severity severity, String message, String recipientKey) {
    }

    public record Draft(boolean processing, List<DataCategory> categories, List<Paragraph> paragraphs,
                        List<Alert> alerts) {

        public String text() {
            return paragraphs.stream().map(Paragraph::text).collect(Collectors.joining("\n\n"));
        }
    }

    public static boolean supports(RequestType type) {
        return type == RequestType.INFORMATION_SHARING || type == RequestType.CONFIRMATION;
    }

    public static Draft compose(Input in) {
        if (!supports(in.type())) {
            throw new IllegalArgumentException("Tipo sem rascunho: " + in.type());
        }
        boolean processing = in.subjectFound();
        List<DataCategory> categories = processing ? categories(in.recipients()) : List.of();
        List<Paragraph> paragraphs = new ArrayList<>();
        paragraphs.add(new Paragraph("Olá, %s.".formatted(firstName(in.requesterName())), List.of()));
        if (in.type() == RequestType.CONFIRMATION) {
            confirmation(in, processing, categories, paragraphs);
        } else {
            sharing(in, processing, paragraphs);
        }
        paragraphs.add(new Paragraph("Se quiser mais detalhes ou exercer outros direitos — como acesso, correção, "
                + "eliminação ou oposição —, responda a esta mensagem.", List.of()));
        return new Draft(processing, categories, List.copyOf(paragraphs), alerts(in, List.copyOf(paragraphs)));
    }

    private static void confirmation(Input in, boolean processing, List<DataCategory> categories,
                                     List<Paragraph> out) {
        String received = received(in);
        if (!processing) {
            out.add(new Paragraph(("Em resposta à sua solicitação%s, informamos que não localizamos dados pessoais seus "
                    + "em nossa base. Por isso, não há tratamento de dados a confirmar (Lei Geral de Proteção de Dados, "
                    + "art. 18, I).").formatted(received), List.of()));
            return;
        }
        out.add(new Paragraph(("Em resposta à sua solicitação%s, confirmamos que tratamos dados pessoais seus (Lei "
                + "Geral de Proteção de Dados, art. 18, I).").formatted(received), List.of()));
        out.add(new Paragraph("Dados tratados: %s.".formatted(joinPt(categories.stream().map(DataCategory::label).toList())),
                in.recipients().stream().map(Recipient::key).toList()));
        List<Recipient> creditors = of(in, DataRecipientType.CREDITOR);
        if (!creditors.isEmpty()) {
            out.add(new Paragraph("Finalidade: cobrança das dívidas com %s%s.".formatted(
                    joinPt(creditors.stream().map(Recipient::displayName).toList()), basesSentence(creditors)),
                    creditors.stream().map(Recipient::key).toList()));
        }
        out.add(new Paragraph("Você também pode pedir uma cópia dos seus dados e saber com quem eles foram "
                + "compartilhados.", List.of()));
    }

    private static void sharing(Input in, boolean processing, List<Paragraph> out) {
        String received = received(in);
        if (!processing) {
            out.add(new Paragraph(("Em resposta à sua solicitação%s, informamos que não localizamos dados pessoais seus "
                    + "em nossa base. Por isso, não há compartilhamento a informar (Lei Geral de Proteção de Dados, "
                    + "art. 18, VII).").formatted(received), List.of()));
            return;
        }
        out.add(new Paragraph(("Em resposta à sua solicitação%s, informamos com quem seus dados pessoais foram "
                + "compartilhados (Lei Geral de Proteção de Dados, art. 18, VII).").formatted(received), List.of()));
        List<Recipient> creditors = of(in, DataRecipientType.CREDITOR);
        if (!creditors.isEmpty()) {
            out.add(section("Empresas credoras — a quem a dívida pertence ou pertenceu. Tratamos seus dados em nome "
                    + "delas para a cobrança:", creditors, r -> r.displayName() + basisClause(r)));
        }
        List<Recipient> providers = of(in, DataRecipientType.PROVIDER);
        if (!providers.isEmpty()) {
            out.add(section("Prestadores de serviço que tratam seus dados em nosso nome, somente para a finalidade "
                    + "indicada:", providers, ResponseDraftComposer::providerLine));
        }
        List<Recipient> firms = of(in, DataRecipientType.LAW_FIRM);
        if (!firms.isEmpty()) {
            out.add(section("Escritórios de advocacia parceiros, para a cobrança judicial ou extrajudicial:", firms,
                    Recipient::displayName));
        }
        List<Recipient> integrations = of(in, DataRecipientType.INTEGRATION);
        if (!integrations.isEmpty()) {
            out.add(new Paragraph("Parte dos seus dados chegou até nós pela integração de sistemas com a empresa "
                    + "credora, que é quem os enviou.", integrations.stream().map(Recipient::key).toList()));
        }
        if (in.unavailableSources().isEmpty()) {
            out.add(new Paragraph("Não compartilhamos seus dados com outras empresas além das listadas acima.",
                    in.recipients().stream().map(Recipient::key).toList()));
        }
    }

    private static Paragraph section(String heading, List<Recipient> recipients,
                                     java.util.function.Function<Recipient, String> line) {
        StringBuilder text = new StringBuilder(heading);
        for (Recipient r : recipients) {
            text.append("\n- ").append(line.apply(r)).append('.');
        }
        return new Paragraph(text.toString(), recipients.stream().map(Recipient::key).toList());
    }

    private static String providerLine(Recipient r) {
        StringBuilder line = new StringBuilder(r.displayName()).append(": ")
                .append(r.processor() != null ? r.processor().purpose().replaceAll("[.\\s]+$", "")
                        : joinPt(r.purposes().stream().sorted().map(ResponseDraftComposer::purposeLabel).toList()));
        ProcessorInfo p = r.processor();
        if (p != null && p.countryCode() != null && !"BR".equals(p.countryCode()) && p.safeguard() != null) {
            line.append(" (dados tratados em ").append(countryName(p.countryCode())).append(", com ")
                    .append(p.safeguard().label()).append(')');
        }
        return line.toString();
    }

    private static String basisClause(Recipient creditor) {
        return creditor.legalBases().isEmpty() ? ""
                : ", com base em " + joinPt(creditor.legalBases().stream().distinct()
                        .map(ResponseDraftComposer::legalBasisLabel).toList());
    }

    private static String basesSentence(List<Recipient> creditors) {
        List<String> bases = creditors.stream().flatMap(c -> c.legalBases().stream()).distinct().sorted()
                .map(ResponseDraftComposer::legalBasisLabel).toList();
        return bases.isEmpty() ? "" : ", com base em " + joinPt(bases);
    }

    private static List<Alert> alerts(Input in, List<Paragraph> paragraphs) {
        List<Alert> alerts = new ArrayList<>();
        for (String source : in.unavailableSources()) {
            alerts.add(new Alert("SOURCE_UNAVAILABLE", Severity.DANGER,
                    "Não foi possível consultar %s agora: o rascunho pode estar incompleto. Tente gerar de novo."
                            .formatted(source), null));
        }
        for (Recipient r : in.recipients()) {
            if (r.type() == DataRecipientType.PROVIDER && r.processor() == null) {
                alerts.add(new Alert("PROCESSOR_UNREGISTERED", Severity.WARNING,
                        "%s não está no cadastro de operadores: cadastre a finalidade, o país e o contrato."
                                .formatted(r.name()), r.key()));
            } else if (r.type() == DataRecipientType.PROVIDER && !r.processor().current()) {
                alerts.add(new Alert("PROCESSOR_NOT_CURRENT", Severity.WARNING,
                        "O cadastro de operador de %s está inativo ou com contrato vencido.".formatted(r.displayName()),
                        r.key()));
            } else if (r.type() == DataRecipientType.CREDITOR && r.legalBases().isEmpty()) {
                alerts.add(new Alert("CREDITOR_WITHOUT_LEGAL_BASIS", Severity.WARNING,
                        "%s não tem base legal vigente registrada.".formatted(r.name()), r.key()));
            }
        }
        int length = paragraphs.stream().mapToInt(p -> p.text().length() + 2).sum() - 2;
        if (length > TEXT_MAX) {
            alerts.add(new Alert("TEXT_TOO_LONG", Severity.WARNING,
                    "O texto tem %d caracteres e a resposta aceita até %d: resuma antes de concluir."
                            .formatted(length, TEXT_MAX), null));
        }
        return List.copyOf(alerts);
    }

    private static List<DataCategory> categories(List<Recipient> recipients) {
        Set<DataCategory> found = EnumSet.of(DataCategory.IDENTIFICATION);
        for (Recipient r : recipients) {
            for (DataSharingPurpose p : r.purposes()) {
                switch (p) {
                    case DEBT_COLLECTION, CREDITOR_INTEGRATION -> found.add(DataCategory.DEBT);
                    case MESSAGING -> {
                        found.add(DataCategory.CONTACT);
                        found.add(DataCategory.MESSAGES);
                    }
                    case PHONE_CALLS -> {
                        found.add(DataCategory.CONTACT);
                        found.add(DataCategory.CALLS);
                    }
                    case PAYMENT_COLLECTION -> found.add(DataCategory.PAYMENT);
                    case VIRTUAL_ASSISTANT -> found.add(DataCategory.MESSAGES);
                    case LEGAL_COLLECTION -> found.add(DataCategory.LEGAL);
                }
            }
        }
        return List.copyOf(found);
    }

    private static List<Recipient> of(Input in, DataRecipientType type) {
        return in.recipients().stream().filter(r -> r.type() == type).toList();
    }

    private static String received(Input in) {
        return in.receivedOn() == null ? "" : " recebida em " + DATE.format(in.receivedOn());
    }

    /** Primeiro nome como informado, com a inicial maiúscula; sem nome, uma saudação neutra. */
    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "titular";
        }
        String first = name.trim().split("\\s+")[0];
        String lower = first.toLowerCase(PT_BR);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String purposeLabel(DataSharingPurpose purpose) {
        return switch (purpose) {
            case DEBT_COLLECTION -> "cobrança da dívida";
            case MESSAGING -> "envio e recebimento de mensagens";
            case PHONE_CALLS -> "ligações telefônicas";
            case PAYMENT_COLLECTION -> "emissão de cobranças Pix e confirmação de pagamentos";
            case VIRTUAL_ASSISTANT -> "atendimento pelo assistente virtual";
            case LEGAL_COLLECTION -> "cobrança judicial ou extrajudicial";
            case CREDITOR_INTEGRATION -> "envio dos dados das dívidas pelo sistema do credor";
        };
    }

    public static String legalBasisLabel(LegalBasis basis) {
        return switch (basis) {
            case EXECUCAO_CONTRATO -> "execução do contrato do qual você é parte (art. 7º, V)";
            case EXERCICIO_REGULAR_DIREITOS -> "exercício regular de direitos (art. 7º, VI)";
            case LEGITIMO_INTERESSE -> "legítimo interesse (art. 7º, IX)";
            case PROTECAO_CREDITO -> "proteção do crédito (art. 7º, X)";
            case CONSENTIMENTO -> "seu consentimento (art. 7º, I)";
            case OBRIGACAO_LEGAL -> "cumprimento de obrigação legal (art. 7º, II)";
        };
    }

    public static String countryName(String code) {
        String name = Locale.of("", code).getDisplayCountry(PT_BR);
        return name == null || name.isBlank() ? code : name;
    }

    /** "a", "a e b", "a, b e c". */
    static String joinPt(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " e " + items.get(items.size() - 1);
    }
}
