package br.com.gestorcobranca.shared.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Conversor Logback que aplica {@link SensitiveDataMasker} à mensagem formatada. Registrado em
 * {@code logback-spring.xml} como {@code %maskedMsg}; é a última barreira contra vazamento de
 * CPF, e-mail e telefone em logs técnicos.
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.convert(event));
    }
}
