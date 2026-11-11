package br.com.gestorcobranca.compliance.domain.model;

/** Papel de um prestador no tratamento dos dados dos devedores (LGPD art. 5º, VI e VII; art. 39). */
public enum ProcessorRole {
    /** Trata dados em nome da assessoria (ex.: plataforma de mensagens contratada pela assessoria). */
    OPERATOR("Operador"),
    /** Contratado pela assessoria enquanto ela é operadora do credor (ex.: PSP, telefonia, IA). */
    SUB_OPERATOR("Suboperador"),
    /** Decide sobre o tratamento junto com a assessoria. */
    JOINT_CONTROLLER("Controlador conjunto");

    private final String label;

    ProcessorRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
