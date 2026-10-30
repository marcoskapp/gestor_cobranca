package br.com.gestorcobranca.shared.ai.bedrock;

import br.com.gestorcobranca.shared.ai.LanguageModel.Attachment;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelMessage;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Imagens anexadas à mensagem da pessoa viram blocos de imagem da Converse API, antes do texto. */
class ConverseTranslatorImageTest {

    private final ConverseTranslator translator = new ConverseTranslator(new JsonCodec(JsonMapper.builder().build()), "");

    @Test
    void attachmentsBecomeImageBlocksBeforeTheText() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        List<Message> messages = translator.messages(List.of(ModelMessage.user("páginas 1 a 1",
                List.of(new Attachment("png", png)))));
        List<ContentBlock> content = messages.getFirst().content();
        assertThat(content).hasSize(2);
        assertThat(content.get(0).image().format()).isEqualTo(ImageFormat.PNG);
        assertThat(content.get(0).image().source().bytes().asByteArray()).isEqualTo(png);
        assertThat(content.get(1).text()).isEqualTo("páginas 1 a 1");
    }

    @Test
    void messagesWithoutAttachmentsAreUnchanged() {
        assertThat(translator.messages(List.of(ModelMessage.user("oi"))).getFirst().content()).hasSize(1);
        assertThat(ModelMessage.user("oi").attachments()).isEmpty();
    }

    @Test
    void onlyPngOrJpegAreAccepted() {
        assertThatThrownBy(() -> new Attachment("gif", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attachment("png", new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
