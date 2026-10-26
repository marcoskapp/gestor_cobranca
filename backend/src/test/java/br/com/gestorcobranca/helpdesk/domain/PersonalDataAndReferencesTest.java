package br.com.gestorcobranca.helpdesk.domain;

import br.com.gestorcobranca.helpdesk.domain.service.PersonalData;
import br.com.gestorcobranca.helpdesk.domain.service.PersonalData.Placeholders;
import br.com.gestorcobranca.helpdesk.domain.service.ReferenceBook;
import br.com.gestorcobranca.helpdesk.domain.service.ReferenceBook.Kind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Marcadores de documento para o modelo e referências opacas da conversa. */
class PersonalDataAndReferencesTest {

    @Test
    void documentsBecomeStablePlaceholdersAcrossTheConversation() {
        Placeholders p = PersonalData.withPlaceholders(Arrays.asList("procure o 529.982.247-25", null,
                "e o 11.222.333/0001-81? e de novo 52998224725, fone 11 98765-4321, maria@x.com"));
        assertThat(p.texts().get(0)).isEqualTo("procure o [DOC1]");
        assertThat(p.texts().get(1)).isNull();
        assertThat(p.texts().get(2)).contains("[DOC2]").contains("de novo [DOC1]").contains("[telefone]")
                .contains("[e-mail]").doesNotContain("98765");
        assertThat(p.resolve("[DOC1]")).isEqualTo("52998224725");
        assertThat(p.resolve("DOC2")).isEqualTo("11222333000181");
        assertThat(p.resolve("[DOC9]")).isNull();
        assertThat(p.resolve("Maria")).isNull();
    }

    @Test
    void maskReplacesEverythingPersonal() {
        assertThat(PersonalData.mask("CPF 529.982.247-25 e-mail a@b.com.br tel (11) 3333-4444"))
                .isEqualTo("CPF [documento] e-mail [e-mail] tel [telefone]");
    }

    @Test
    void referencesAreStableTypedAndPersistable() {
        ReferenceBook book = ReferenceBook.empty();
        UUID debtor = UUID.randomUUID();
        UUID agreement = UUID.randomUUID();
        assertThat(book.refFor(Kind.DEBTOR, debtor)).isEqualTo("D1");
        assertThat(book.refFor(Kind.DEBTOR, UUID.randomUUID())).isEqualTo("D2");
        assertThat(book.refFor(Kind.DEBTOR, debtor)).isEqualTo("D1");
        assertThat(book.refFor(Kind.AGREEMENT, agreement)).isEqualTo("A1");
        assertThat(book.resolve(Kind.DEBTOR, "d1")).contains(debtor);
        // referência de outro tipo não serve
        assertThat(book.resolve(Kind.AGREEMENT, "D1")).isEmpty();
        assertThat(book.resolve(Kind.DEBTOR, "D9")).isEmpty();

        ReferenceBook restored = ReferenceBook.of(Map.<String, Object>copyOf(book.toMap()));
        assertThat(restored.resolve(Kind.AGREEMENT, "A1")).contains(agreement);
        assertThat(restored.refFor(Kind.DEBTOR, UUID.randomUUID())).isEqualTo("D3");
        // entradas adulteradas são ignoradas
        assertThat(ReferenceBook.of(Map.of("D1", "DEBTOR:nao-e-uuid", "X", "DEBTOR:" + debtor)).toMap()).isEmpty();
    }
}
