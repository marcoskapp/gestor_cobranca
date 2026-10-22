package br.com.gestorcobranca.shared.audit;

/**
 * Trilha de auditoria append-only. O registro é gravado <strong>na mesma transação</strong> da
 * alteração auditada: não existe alteração confirmada sem o respectivo registro, nem registro
 * de alteração desfeita.
 * <p>
 * Integridade: cada registro guarda o hash do seu conteúdo; um job encadeia os hashes por
 * tenant ({@code chain_hash}), tornando detectável qualquer remoção ou adulteração. O papel de
 * banco da aplicação não tem permissão de UPDATE/DELETE na tabela.
 */
public interface AuditTrail {

    void record(AuditEntry entry);
}
