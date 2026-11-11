package br.com.gestorcobranca.compliance.domain.model;

/** Garantia da transferência internacional de dados (LGPD art. 33). */
public enum TransferSafeguard {
    /** I — país ou organismo com grau de proteção adequado reconhecido pela ANPD. */
    ADEQUACY_DECISION("país com proteção de dados reconhecida como adequada pela ANPD", "art. 33, I"),
    /** II, a — cláusulas contratuais específicas para a transferência. */
    SPECIFIC_CONTRACTUAL_CLAUSES("cláusulas contratuais específicas de proteção de dados", "art. 33, II, a"),
    /** II, b — cláusulas-padrão contratuais aprovadas pela ANPD. */
    STANDARD_CONTRACTUAL_CLAUSES("cláusulas-padrão de proteção de dados aprovadas pela ANPD", "art. 33, II, b"),
    /** II, c — normas corporativas globais. */
    GLOBAL_CORPORATE_RULES("normas corporativas globais de proteção de dados", "art. 33, II, c"),
    /** II, d — selos, certificados e códigos de conduta. */
    SEALS_AND_CERTIFICATES("selos, certificados ou códigos de conduta reconhecidos", "art. 33, II, d"),
    /** V — autorização específica da ANPD. */
    ANPD_AUTHORIZATION("autorização da ANPD", "art. 33, V"),
    /** VIII — consentimento específico e destacado do titular. */
    SPECIFIC_CONSENT("seu consentimento específico", "art. 33, VIII"),
    /** IX — necessária à execução de contrato, ao exercício regular de direitos ou a obrigação legal (art. 7º, II, V e VI). */
    LEGAL_NECESSITY("necessidade para cumprir contrato, obrigação legal ou exercer direitos", "art. 33, IX");

    private final String label;
    private final String article;

    TransferSafeguard(String label, String article) {
        this.label = label;
        this.article = article;
    }

    /** Texto em linguagem simples, para a resposta ao titular. */
    public String label() {
        return label;
    }

    /** Fundamento na LGPD. */
    public String article() {
        return article;
    }
}
