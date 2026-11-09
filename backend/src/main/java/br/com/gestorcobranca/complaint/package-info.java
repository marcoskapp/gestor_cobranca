/**
 * Reclamações (I5 da revisão de produto de 11/2026): reclamações recebidas por órgãos e canais externos — Procon,
 * consumidor.gov.br, Banco Central (RDR) ou ouvidoria do credor, Reclame Aqui e outros — com protocolo externo,
 * prazo de resposta por canal, responsável, resposta, encerramento, vínculo ao devedor, ao caso e ao credor,
 * evidências (quarentena e verificação de malware) e indicador por credor (no prazo × fora do prazo). Ver
 * {@code docs/tecnico/desenho/conformidade.md} §3.
 * <p>
 * Módulo próprio (e não parte do compliance) porque a reclamação se vincula ao caso de cobrança: o compliance é
 * dependência do collection e não pode depender dele de volta. Dependências: {@code organization}, {@code identity},
 * {@code debt} e {@code collection} (interfaces {@code usecases}). Pendências em "Minhas pendências"
 * ({@code PendingWorkSource}). A integração automática com consumidor.gov.br e Procon é provedor externo (issue, não
 * código).
 */
@ApplicationModule(displayName = "Reclamações",
        allowedDependencies = {"shared", "organization :: usecases", "identity :: usecases", "debt :: usecases",
                "collection :: usecases"})
package br.com.gestorcobranca.complaint;

import org.springframework.modulith.ApplicationModule;
