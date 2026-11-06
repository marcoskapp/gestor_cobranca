package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Período em que um pagamento informado recusado continua sinalizado no acordo e no caso (em dias inteiros,
 * garantidos por {@link PaymentProperties.ReviewStatus}).
 */
@Component
class PaymentManualParameters implements ManualParameterSource {

    private final PaymentProperties properties;

    PaymentManualParameters(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "payment";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        return Map.of("rejectedReviewWindowDays",
                BigDecimal.valueOf(properties.reviewStatus().rejectedWindow().toDays()));
    }
}
