package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTemplateTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static MessageTemplate.Content content(String subject, String providerName) {
        return new MessageTemplate.Content("Lembrete", TemplatePurpose.REMINDER, subject, "Olá {{nome}}", "[]", null,
                providerName, null);
    }

    @Test
    void whatsappRequiresApprovalToPublishAndEditingResetsIt() {
        MessageTemplate t = new MessageTemplate(null, "LEMBRETE", 1, ChannelType.WHATSAPP, content(null, null));
        assertThat(t.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThatThrownBy(() -> t.publish(NOW, UUID.randomUUID())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "template.not_approved");
        assertThatThrownBy(() -> t.registerApproval(ApprovalStatus.APPROVED, null, null, null, NOW, UUID.randomUUID()))
                .hasFieldOrPropertyWithValue("code", "template.provider_name_required");
        t.registerApproval(ApprovalStatus.APPROVED, "lembrete_v1", "123", null, NOW, UUID.randomUUID());
        t.edit(content(null, "lembrete_v1"));
        assertThat(t.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        t.registerApproval(ApprovalStatus.APPROVED, null, "124", null, NOW, UUID.randomUUID());
        t.publish(NOW, UUID.randomUUID());
        assertThat(t.isUsable()).isTrue();
        assertThatThrownBy(() -> t.edit(content(null, null))).hasFieldOrPropertyWithValue("code", "template.not_draft");
        t.retire(NOW);
        assertThat(t.getStatus()).isEqualTo(TemplateStatus.RETIRED);
    }

    @Test
    void rejectionNeedsReasonAndEmailNeedsSubject() {
        MessageTemplate t = new MessageTemplate(null, "LEMBRETE", 1, ChannelType.WHATSAPP, content(null, null));
        assertThatThrownBy(() -> t.registerApproval(ApprovalStatus.REJECTED, "x", null, " ", NOW, UUID.randomUUID()))
                .hasFieldOrPropertyWithValue("code", "template.rejection_note_required");
        assertThatThrownBy(() -> new MessageTemplate(null, "AVISO", 1, ChannelType.EMAIL, content(null, null)))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "template.subject_required");
        MessageTemplate email = new MessageTemplate(UUID.randomUUID(), "AVISO", 2, ChannelType.EMAIL, content("Aviso", null));
        assertThat(email.getApprovalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThatThrownBy(() -> email.registerApproval(ApprovalStatus.APPROVED, "x", null, null, NOW, UUID.randomUUID()))
                .hasFieldOrPropertyWithValue("code", "template.approval_not_applicable");
        email.publish(NOW, UUID.randomUUID());
        assertThat(email.isUsable()).isTrue();
    }

    @Test
    void providerCategoryOnlyOnWhatsappDefaultingToUtility() {
        MessageTemplate whatsapp = new MessageTemplate(null, "LEMBRETE", 1, ChannelType.WHATSAPP, content(null, null));
        assertThat(whatsapp.getProviderCategory()).isEqualTo(ProviderCategory.UTILITY);
        assertThat(whatsapp.isAuthentication()).isFalse();
        MessageTemplate otp = new MessageTemplate(null, "PORTAL_CODIGO_WHATSAPP", 1, ChannelType.WHATSAPP,
                new MessageTemplate.Content("Código", TemplatePurpose.SERVICE, null, "{{codigo}} é o seu código.", "[]",
                        null, "portal_codigo", ProviderCategory.AUTHENTICATION));
        assertThat(otp.isAuthentication()).isTrue();
        assertThatThrownBy(() -> new MessageTemplate(null, "AVISO", 1, ChannelType.EMAIL,
                new MessageTemplate.Content("Aviso", TemplatePurpose.REMINDER, "Aviso", "Olá", "[]", null, null,
                        ProviderCategory.UTILITY)))
                .hasFieldOrPropertyWithValue("code", "template.category_not_applicable");
        assertThat(new MessageTemplate(null, "AVISO", 1, ChannelType.EMAIL, content("Aviso", null)).getProviderCategory())
                .isNull();
    }
}
