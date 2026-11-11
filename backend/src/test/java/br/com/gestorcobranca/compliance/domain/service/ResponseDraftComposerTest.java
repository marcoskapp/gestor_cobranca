package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.LegalBasis;
import br.com.gestorcobranca.compliance.domain.model.ProcessorRole;
import br.com.gestorcobranca.compliance.domain.model.RequestType;
import br.com.gestorcobranca.compliance.domain.model.TransferSafeguard;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.Alert;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.DataCategory;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.Draft;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.Input;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.Paragraph;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.ProcessorInfo;
import br.com.gestorcobranca.compliance.domain.service.ResponseDraftComposer.Recipient;
import br.com.gestorcobranca.shared.privacy.DataRecipientType;
import br.com.gestorcobranca.shared.privacy.DataSharingPurpose;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseDraftComposerTest {

    private static final LocalDate RECEIVED = LocalDate.of(2026, 11, 3);

    private static final Recipient BANK = new Recipient(DataRecipientType.CREDITOR,
            "debt.creditor:00000000-0000-0000-0000-000000000001", "Banco Fictício", Set.of(DataSharingPurpose.DEBT_COLLECTION),
            List.of(LegalBasis.EXERCICIO_REGULAR_DIREITOS), null);
    private static final Recipient STORE_WITHOUT_BASIS = new Recipient(DataRecipientType.CREDITOR,
            "debt.creditor:00000000-0000-0000-0000-000000000002", "Loja Exemplo", Set.of(DataSharingPurpose.DEBT_COLLECTION),
            List.of(), null);
    private static final Recipient WHATSAPP = new Recipient(DataRecipientType.PROVIDER, "communication.whatsapp_cloud",
            "WhatsApp Business (Meta)", Set.of(DataSharingPurpose.MESSAGING), List.of(),
            new ProcessorInfo("Mensageria Exemplo Ltda.", ProcessorRole.SUB_OPERATOR, "Entrega de mensagens de WhatsApp.",
                    "US", TransferSafeguard.STANDARD_CONTRACTUAL_CLAUSES, true));
    private static final Recipient TELEPHONY = new Recipient(DataRecipientType.PROVIDER, "telephony.twilio", "Twilio",
            Set.of(DataSharingPurpose.PHONE_CALLS), List.of(), null);
    private static final Recipient FIRM = new Recipient(DataRecipientType.LAW_FIRM,
            "legal.law_firm:00000000-0000-0000-0000-000000000003", "Advocacia Fictícia", Set.of(DataSharingPurpose.LEGAL_COLLECTION),
            List.of(), null);

    private static Draft sharing(List<Recipient> recipients, List<String> unavailable) {
        return ResponseDraftComposer.compose(new Input(RequestType.INFORMATION_SHARING, "MARIA da Silva", RECEIVED, true,
                recipients, unavailable));
    }

    @Test
    void sharingDraftListsEachGroupInPlainLanguageAndTracesParagraphsToRecipients() {
        Draft draft = sharing(List.of(BANK, WHATSAPP, TELEPHONY, FIRM), List.of());
        String text = draft.text();

        assertThat(text).startsWith("Olá, Maria.")
                .contains("recebida em 03/11/2026")
                .contains("art. 18, VII")
                .contains("- Banco Fictício, com base em exercício regular de direitos (art. 7º, VI).")
                // nome, finalidade e país vêm do cadastro do operador
                .contains("- Mensageria Exemplo Ltda.: Entrega de mensagens de WhatsApp (dados tratados em Estados Unidos, "
                        + "com cláusulas-padrão de proteção de dados aprovadas pela ANPD).")
                // sem cadastro: finalidade do sistema, nome da plataforma
                .contains("- Twilio: ligações telefônicas.")
                .contains("- Advocacia Fictícia.")
                .contains("Não compartilhamos seus dados com outras empresas além das listadas acima.");
        // sem códigos internos nem enums no texto
        assertThat(text).doesNotContain("PROVIDER", "CREDITOR", "communication.", "debt.creditor", "WHATSAPP_CLOUD",
                "EXERCICIO_REGULAR");

        Paragraph providers = draft.paragraphs().stream().filter(p -> p.text().startsWith("Prestadores")).findFirst()
                .orElseThrow();
        assertThat(providers.recipientKeys()).containsExactly("communication.whatsapp_cloud", "telephony.twilio");
        assertThat(draft.alerts()).extracting(Alert::code).containsExactly("PROCESSOR_UNREGISTERED");
        assertThat(draft.alerts().get(0).recipientKey()).isEqualTo("telephony.twilio");
    }

    @Test
    void manualProcessorWithoutRecordedUsageIsListedWithTheRegisteredPurpose() {
        // operador cadastrado à mão (ex.: hospedagem): nenhuma fonte registra uso, então vem sem finalidade do sistema;
        // a linha usa a finalidade do cadastro e a frase de completude passa a ser verdadeira
        Recipient hosting = new Recipient(DataRecipientType.PROVIDER, "custom.hospedagem", "Nuvem Fictícia Ltda.",
                Set.of(), List.of(), new ProcessorInfo("Nuvem Fictícia Ltda.", ProcessorRole.OPERATOR,
                        "Hospedagem do sistema e dos backups.", "BR", null, true));
        Draft draft = sharing(List.of(BANK, WHATSAPP, hosting), List.of());

        assertThat(draft.text())
                .contains("- Nuvem Fictícia Ltda.: Hospedagem do sistema e dos backups.")
                .contains("Não compartilhamos seus dados com outras empresas além das listadas acima.")
                .doesNotContain("custom.");
        assertThat(draft.paragraphs()).filteredOn(p -> p.text().startsWith("Prestadores")).singleElement()
                .satisfies(p -> assertThat(p.recipientKeys()).containsExactly("communication.whatsapp_cloud",
                        "custom.hospedagem"));
        assertThat(draft.alerts()).isEmpty();
        // sem finalidade do sistema, não acrescenta categoria de dado na confirmação
        Draft confirmation = ResponseDraftComposer.compose(new Input(RequestType.CONFIRMATION, "ana", RECEIVED, true,
                List.of(BANK, hosting), List.of()));
        assertThat(confirmation.categories()).containsExactly(DataCategory.IDENTIFICATION, DataCategory.DEBT);
    }

    @Test
    void alertsCreditorWithoutLegalBasisInactiveProcessorAndUnavailableSourceWithoutClaimingCompleteness() {
        Recipient inactive = new Recipient(DataRecipientType.PROVIDER, "telephony.plivo", "Plivo",
                Set.of(DataSharingPurpose.PHONE_CALLS), List.of(),
                new ProcessorInfo("Plivo", ProcessorRole.SUB_OPERATOR, "Ligações", "BR", null, false));
        Draft draft = sharing(List.of(STORE_WITHOUT_BASIS, inactive), List.of("Telefonia"));

        assertThat(draft.alerts()).extracting(Alert::code)
                .containsExactly("SOURCE_UNAVAILABLE", "CREDITOR_WITHOUT_LEGAL_BASIS", "PROCESSOR_NOT_CURRENT");
        assertThat(draft.alerts().get(0).severity()).isEqualTo(ResponseDraftComposer.Severity.DANGER);
        // com fonte indisponível o texto não afirma que não há outros destinatários
        assertThat(draft.text()).doesNotContain("Não compartilhamos").contains("- Loja Exemplo.");
    }

    @Test
    void withoutSubjectInTheBaseThereIsNothingToShareOrConfirm() {
        Draft sharing = ResponseDraftComposer.compose(new Input(RequestType.INFORMATION_SHARING, "João", RECEIVED, false,
                List.of(), List.of()));
        assertThat(sharing.processing()).isFalse();
        assertThat(sharing.text()).contains("não localizamos dados pessoais seus").contains("não há compartilhamento");

        Draft confirmation = ResponseDraftComposer.compose(new Input(RequestType.CONFIRMATION, null, null, false,
                List.of(), List.of()));
        assertThat(confirmation.text()).startsWith("Olá, titular.").contains("não há tratamento de dados a confirmar");
        assertThat(confirmation.categories()).isEmpty();
    }

    @Test
    void confirmationStatesCategoriesCreditorsAndLegalBasis() {
        Draft draft = ResponseDraftComposer.compose(new Input(RequestType.CONFIRMATION, "ana", RECEIVED, true,
                List.of(BANK, WHATSAPP, TELEPHONY), List.of()));

        assertThat(draft.processing()).isTrue();
        assertThat(draft.categories()).containsExactly(DataCategory.IDENTIFICATION, DataCategory.DEBT,
                DataCategory.CONTACT, DataCategory.MESSAGES, DataCategory.CALLS);
        assertThat(draft.text()).startsWith("Olá, Ana.")
                .contains("confirmamos que tratamos dados pessoais seus")
                .contains("Dados tratados: identificação (nome e CPF/CNPJ), informações das dívidas, acordos e pagamentos, "
                        + "contatos (telefone e e-mail), mensagens trocadas conosco e registro das ligações telefônicas.")
                .contains("Finalidade: cobrança das dívidas com Banco Fictício, com base em exercício regular de direitos");
    }

    @Test
    void warnsWhenTheTextExceedsTheResponseLimit() {
        List<Recipient> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(new Recipient(DataRecipientType.CREDITOR, "debt.creditor:00000000-0000-0000-0000-0000000001%02d"
                    .formatted(i), "Credor fictício de nome bem comprido número " + i,
                    Set.of(DataSharingPurpose.DEBT_COLLECTION), List.of(LegalBasis.PROTECAO_CREDITO), null));
        }
        Draft draft = sharing(many, List.of());
        assertThat(draft.text().length()).isGreaterThan(ResponseDraftComposer.TEXT_MAX);
        assertThat(draft.alerts()).extracting(Alert::code).containsExactly("TEXT_TOO_LONG");
    }

    @Test
    void onlyConfirmationAndSharingHaveDrafts() {
        assertThat(ResponseDraftComposer.supports(RequestType.ACCESS)).isFalse();
        assertThatThrownBy(() -> ResponseDraftComposer.compose(new Input(RequestType.ACCESS, "x", RECEIVED, true,
                List.of(), List.of()))).isInstanceOf(IllegalArgumentException.class);
    }
}
