package br.com.gestorcobranca.shared.security;

/** Operação fora do escopo de credor/permissão do ator (HTTP 403). */
public class ScopeAccessDeniedException extends RuntimeException {

    public ScopeAccessDeniedException() {
        super("Acesso negado para este recurso");
    }
}
