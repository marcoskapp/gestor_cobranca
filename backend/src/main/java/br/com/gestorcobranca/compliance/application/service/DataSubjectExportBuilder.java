package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;
import br.com.gestorcobranca.compliance.domain.repository.ContactAttemptRepository;
import br.com.gestorcobranca.compliance.domain.repository.ContactRestrictionRepository;
import br.com.gestorcobranca.compliance.domain.repository.DataSubjectRequestRepository;
import br.com.gestorcobranca.compliance.domain.repository.LegalBasisRecordRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries.DebtorSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monta o arquivo de dados do titular (LGPD art. 18, II e V; art. 19): identificação e contatos
 * sem máscara, dívidas por credor, credores com quem os dados são tratados e a base legal de
 * cada um, restrições de contato, tentativas de contato e solicitações anteriores.
 * <p>
 * Documento e contatos são revelados pelas portas do módulo debt, que exigem
 * {@code DEBTOR_SENSITIVE_VIEW} e registram cada revelação no log de acesso.
 */
@Component
class DataSubjectExportBuilder {

    static final String FORMAT_VERSION = "1";

    private final DebtorUseCases debtors;
    private final DebtorContactUseCases contacts;
    private final DebtorSheetQueries sheets;
    private final CreditorUseCases creditors;
    private final LegalBasisRecordRepository legalBases;
    private final ContactRestrictionRepository restrictions;
    private final ContactAttemptRepository attempts;
    private final DataSubjectRequestRepository requests;
    private final JsonCodec json;

    DataSubjectExportBuilder(DebtorUseCases debtors, DebtorContactUseCases contacts, DebtorSheetQueries sheets,
                             CreditorUseCases creditors, LegalBasisRecordRepository legalBases,
                             ContactRestrictionRepository restrictions, ContactAttemptRepository attempts,
                             DataSubjectRequestRepository requests, JsonCodec json) {
        this.debtors = debtors;
        this.contacts = contacts;
        this.sheets = sheets;
        this.creditors = creditors;
        this.legalBases = legalBases;
        this.restrictions = restrictions;
        this.attempts = attempts;
        this.requests = requests;
        this.json = json;
    }

    byte[] build(DataSubjectRequest request, Instant now) {
        UUID debtorId = request.getDebtorId();
        DebtorView debtor = debtors.get(debtorId);
        DebtorSummary summary = sheets.summary(debtorId);

        Map<String, Object> titular = new LinkedHashMap<>();
        titular.put("id", debtor.id());
        titular.put("nome", debtor.name());
        titular.put("tipoPessoa", debtor.personType());
        titular.put("documento", "ANONYMIZED".equals(debtor.status()) ? null : debtors.revealDocument(debtorId).document());
        titular.put("dataNascimento", debtor.birthDate());
        titular.put("observacoes", debtor.notes());
        titular.put("situacao", debtor.status());
        titular.put("cadastradoEm", debtor.createdAt());

        List<Map<String, Object>> contactRows = contacts.list(debtorId).stream().map(c -> contactRow(debtorId, c)).toList();

        List<UUID> creditorIds = summary.creditors().stream().map(DebtorSheetQueries.CreditorPosition::creditorId).toList();
        Map<UUID, CreditorView> creditorById = creditors.getAll(creditorIds).stream()
                .collect(Collectors.toMap(CreditorView::id, Function.identity()));
        Map<UUID, List<LegalBasisRecord>> basesByCreditor = legalBases.findByCreditorIdIn(creditorIds).stream()
                .collect(Collectors.groupingBy(LegalBasisRecord::getCreditorId));

        List<Map<String, Object>> creditorRows = summary.creditors().stream().map(position -> {
            Map<String, Object> row = new LinkedHashMap<>();
            CreditorView creditor = creditorById.get(position.creditorId());
            row.put("credorId", position.creditorId());
            row.put("credor", creditor == null ? null : creditor.legalName());
            row.put("cnpjCredor", creditor == null ? null : creditor.document());
            row.put("saldoEmAberto", position.openBalance());
            row.put("titulos", position.titles());
            row.put("basesLegais", basesByCreditor.getOrDefault(position.creditorId(), List.of()).stream()
                    .map(DataSubjectExportBuilder::legalBasisRow).toList());
            return row;
        }).toList();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("formato", "gestor-cobranca/dados-do-titular");
        document.put("versaoFormato", FORMAT_VERSION);
        document.put("geradoEm", now);
        document.put("solicitacaoId", request.getId());
        document.put("tipoSolicitacao", request.getType().name());
        document.put("titular", titular);
        document.put("contatos", contactRows);
        document.put("credores", creditorRows);
        document.put("restricoesDeContato", restrictions.findByDebtorIdOrderByCreatedAtAsc(debtorId).stream()
                .map(DataSubjectExportBuilder::restrictionRow).toList());
        document.put("tentativasDeContato", attempts.findByDebtorIdOrderByAttemptedAtDesc(debtorId).stream()
                .map(ContactAttemptQueryService::view).toList());
        document.put("solicitacoesDoTitular", requests.findByDebtorIdOrderByReceivedAtDesc(debtorId).stream()
                .map(DataSubjectExportBuilder::requestRow).toList());
        return json.toJson(document).getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> contactRow(UUID debtorId, ContactView c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tipo", c.type());
        row.put("valor", contacts.revealContact(debtorId, c.id()).value());
        row.put("classificacao", c.label());
        row.put("whatsapp", c.whatsapp());
        row.put("origem", c.source());
        row.put("situacao", c.status());
        return row;
    }

    private static Map<String, Object> legalBasisRow(LegalBasisRecord r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("baseLegal", r.getLegalBasis().name());
        row.put("papelDaAssessoria", r.getProcessingRole().name());
        row.put("carteiraId", r.getPortfolioId());
        row.put("referenciaContratual", r.getContractReference());
        row.put("vigenteDe", r.getValidFrom());
        row.put("vigenteAte", r.getValidUntil());
        row.put("retencaoMeses", r.getRetentionMonths());
        return row;
    }

    private static Map<String, Object> restrictionRow(ContactRestriction r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tipo", r.getType().name());
        row.put("credorId", r.getCreditorId());
        row.put("canal", ComplianceEnums.name(r.getChannel()));
        row.put("contato", r.getContactValueMasked());
        row.put("motivo", r.getReason());
        row.put("inicio", r.getStartsAt());
        row.put("fim", r.getEndsAt());
        row.put("situacao", r.getStatus().name());
        row.put("revogadaEm", r.getRevokedAt());
        return row;
    }

    private static Map<String, Object> requestRow(DataSubjectRequest r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("tipo", r.getType().name());
        row.put("situacao", r.getStatus().name());
        row.put("recebidaEm", r.getReceivedAt());
        row.put("prazo", r.getDueAt());
        row.put("concluidaEm", r.getCompletedAt());
        return row;
    }
}
