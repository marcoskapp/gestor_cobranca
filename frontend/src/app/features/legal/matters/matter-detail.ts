import { Component, computed, effect, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DocumentDisplay } from '../../../shared/components/document-display/document-display';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { describeStatus } from '../../../shared/status/status';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { UserLookup } from '../../admin/data/user.lookup';
import { zonedNow } from '../../collection/data/collection.rules';
import { COLLATERAL_TYPE_LABELS } from '../../debt/data/debt.labels';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { FieldOption, FormDialogService, options } from '../components/form-dialog';
import { LegalMattersApi, LegalOperationsApi, LegalProceedingsApi } from '../data/legal.api';
import {
  ADVANCER_LABELS,
  AUTH_CHANNEL_LABELS,
  CHECKLIST_STATUS_LABELS,
  CLOSURE_LABELS,
  COUNTING_LABELS,
  DEADLINE_KIND_LABELS,
  DOCUMENT_KIND_LABELS,
  DOCUMENT_LINK_LABELS,
  EXPENSE_TYPE_LABELS,
  FLAG_LABELS,
  JUDICIAL_ROUTES,
  MEASURE_NEXT,
  MEASURE_TYPE_LABELS,
  ORIGIN_LABELS,
  RESPONSIBLE_LABELS,
  ROUTE_LABELS,
  STAGE_ACTIONS,
  START_EVENT_LABELS,
  WARNING_FLAGS,
} from '../data/legal.labels';
import { AuthorizationView, DeadlineView, DocumentView, ExpenseView, LegalStage, MeasureView } from '../data/legal.models';
import { budgetUsage, cnjValid, deadlineText, deadlineTone, isFinalStage, isPartnerUser } from '../data/legal.rules';
import { LawsuitCard } from './lawsuit-card';
import { ViabilityDialog, ViabilityDialogData } from './viability-dialog';

/** Abas da ficha (o parâmetro `?aba=` abre direto na aba). */
export const MATTER_TABS = ['resumo', 'viabilidade', 'autorizacao', 'medidas', 'processos', 'prazos', 'custas', 'documentos'] as const;

const routeOptions = (only?: readonly string[]) => options(ROUTE_LABELS, only);

/**
 * Ficha do caso jurídico: resumo com valores, sinalizações, títulos e linha do tempo das fases;
 * viabilidade; autorização do credor com evidência; medidas extrajudiciais; processos (número CNJ,
 * andamentos, audiências); prazos; custas; documentos. Comandos conforme a permissão e a fase.
 */
@Component({
  selector: 'app-matter-detail',
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatTabsModule,
    MatTooltipModule,
    Amount,
    DocumentDisplay,
    EmptyState,
    PageHeader,
    Skeleton,
    StatusChip,
    AppDatePipe,
    LawsuitCard,
  ],
  templateUrl: './matter-detail.html',
  styleUrl: './matter-detail.scss',
})
export class MatterDetail {
  readonly id = input.required<string>();
  readonly aba = input<string>();

  private readonly mattersApi = inject(LegalMattersApi);
  private readonly proceedingsApi = inject(LegalProceedingsApi);
  private readonly operationsApi = inject(LegalOperationsApi);
  private readonly auth = inject(AuthService);
  private readonly forms = inject(FormDialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly userLookup = inject(UserLookup);

  protected readonly routes = ROUTE_LABELS;
  protected readonly flagLabels = FLAG_LABELS;
  protected readonly originLabels = ORIGIN_LABELS;
  protected readonly responsibleLabels = RESPONSIBLE_LABELS;
  protected readonly closureLabels = CLOSURE_LABELS;
  protected readonly stageActions = STAGE_ACTIONS;
  protected readonly measureTypes = MEASURE_TYPE_LABELS;
  protected readonly deadlineKinds = DEADLINE_KIND_LABELS;
  protected readonly expenseTypes = EXPENSE_TYPE_LABELS;
  protected readonly advancers = ADVANCER_LABELS;
  protected readonly documentKinds = DOCUMENT_KIND_LABELS;
  protected readonly authChannels = AUTH_CHANNEL_LABELS;
  protected readonly checklistLabels = CHECKLIST_STATUS_LABELS;
  protected readonly tabs = MATTER_TABS;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.mattersApi.sheet(params) });
  protected readonly sheet = valueOr(this.resource, undefined);
  protected readonly matter = computed(() => this.sheet()?.matter ?? null);
  protected readonly tab = linkedSignal(() => Math.max(0, MATTER_TABS.indexOf((this.aba() ?? 'resumo') as (typeof MATTER_TABS)[number])));

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.matter()?.creditorId]);
  protected readonly users = nameMap(
    inject(UserNames),
    () => {
      const s = this.sheet();
      return s ? [s.matter.responsibleUserId, ...s.history.map((h) => h.actorId), ...s.deadlines.map((d) => d.responsibleUserId)] : [];
    },
    { batch: USER_NAMES_BATCH },
  );

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.LEGAL_MANAGE));
  protected readonly canAuthorize = computed(() => this.auth.hasPermission(Permission.LEGAL_AUTHORIZE));
  protected readonly canExpense = computed(() => this.auth.hasPermission(Permission.LEGAL_EXPENSE_MANAGE));
  protected readonly canUpload = computed(() => this.canManage() || this.canExpense());
  protected readonly canSeeCase = computed(() => this.auth.hasPermission(Permission.CASE_VIEW));
  protected readonly partner = computed(() => isPartnerUser(this.auth.user()?.roles));

  /** Transições possíveis agora (aceitar indicação exige também `LEGAL_AUTHORIZE`). */
  protected readonly transitions = computed(() => {
    const m = this.matter();
    if (!m || !this.canManage()) {
      return [];
    }
    return m.nextStages.filter((s) => !(m.stage === 'ELIGIBLE' && s === 'UNDER_REVIEW') || this.canAuthorize());
  });
  protected readonly pendingAuthorization = computed(() => this.sheet()?.authorizations.find((a) => a.status === 'REQUESTED') ?? null);
  protected readonly grantedAuthorization = computed(() => this.sheet()?.authorizations.find((a) => a.status === 'GRANTED') ?? null);
  protected readonly budget = computed(() => (this.matter() ? budgetUsage(this.matter()!) : null));
  protected readonly final = computed(() => !!this.matter() && isFinalStage(this.matter()!.stage));
  protected readonly history = computed(() => [...(this.sheet()?.history ?? [])].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)));
  protected readonly deadlines = computed(() => [...(this.sheet()?.deadlines ?? [])].sort((a, b) => a.dueDate.localeCompare(b.dueDate)));
  private readonly today = zonedNow().date;

  /** Escritórios para atribuição (lista só para usuários internos). */
  private readonly firms = rxResource({
    params: () => (this.canAuthorize() ? true : undefined),
    stream: () => this.operationsApi.firms().pipe(catchError(() => of([]))),
  });
  private readonly firmList = valueOr(this.firms, []);

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.sheet()?.debtor?.name ?? null));
  }

  protected warn(flag: string): boolean {
    return WARNING_FLAGS.includes(flag);
  }

  protected stageLabel(stage: LegalStage | null): string {
    return stage ? describeStatus('legalStage', stage).label : '—';
  }

  protected routeText(routes: readonly string[] | null | undefined): string {
    return routes?.length ? routes.map((r) => ROUTE_LABELS[r as keyof typeof ROUTE_LABELS] ?? r).join(', ') : '—';
  }

  protected deadlineTone(d: DeadlineView) {
    return deadlineTone(d, this.today);
  }

  protected deadlineText(d: DeadlineView): string {
    return deadlineText(d, this.today);
  }

  protected userName(id: string | null): string {
    return id ? (id === this.auth.user()?.id ? 'Você' : this.users.name(id)) : '—';
  }

  private reload(message: string): void {
    this.notifications.success(message);
    this.resource.reload();
  }

  private impact(): { label: string; value: string }[] {
    const s = this.sheet()!;
    return [
      { label: 'Devedor', value: s.debtor ? `${s.debtor.name} · ${s.debtor.maskedDocument}` : 'Devedor do caso' },
      { label: 'Credor', value: this.creditors.name(s.matter.creditorId) },
      { label: 'Dívida', value: formatAmount(s.matter.debtAmount) },
      { label: 'Fase atual', value: this.stageLabel(s.matter.stage) },
    ];
  }

  // ------------------------------------------------------------------ fases e responsável

  protected transition(stage: LegalStage): void {
    const m = this.matter()!;
    if (stage === 'AWAITING_AUTHORIZATION') {
      this.requestAuthorization();
      return;
    }
    this.forms
      .open({
        title: STAGE_ACTIONS[stage],
        message: `O caso passa para "${this.stageLabel(stage)}". A mudança fica na linha do tempo e na auditoria.`,
        submitLabel: 'Confirmar',
        details: this.impact(),
        warning: stage === 'CLOSED' ? 'Prazos pendentes são cancelados (exceto baixa de restrição) e a retenção na cobrança é retirada.' : undefined,
        fields: [
          { key: 'reason', label: 'Motivo', type: 'textarea', required: true, maxLength: 500 },
          { key: 'closureReason', label: 'Motivo do encerramento', type: 'select', required: stage === 'CLOSED', options: options(CLOSURE_LABELS), showIf: () => stage === 'CLOSED' },
          {
            key: 'agreementId',
            label: 'Identificador do acordo (opcional)',
            type: 'text',
            hint: 'Acordos formalizados no sistema chegam sozinhos; informe só para acordo feito fora dele.',
            showIf: () => stage === 'SETTLED_IN_COURT',
            validate: (v) => (/^[0-9a-f-]{36}$/i.test(String(v)) ? null : 'Identificador inválido.'),
          },
        ],
        submit: (v) => this.mattersApi.transition(m.id, { stage, reason: v['reason'] as string, closureReason: (v['closureReason'] as string) ?? null, agreementId: (v['agreementId'] as string) ?? null }),
      })
      .subscribe(() => this.reload(`Caso em "${this.stageLabel(stage)}".`));
  }

  protected assignResponsible(): void {
    const m = this.matter()!;
    const firms: FieldOption[] = this.firmList().filter((f) => f.active).map((f) => ({ value: f.id, label: f.name }));
    this.forms
      .open({
        title: 'Responsável pelo caso',
        message: 'Advogados do escritório parceiro passam a ver somente os casos atribuídos ao escritório.',
        submitLabel: 'Salvar',
        fields: [
          { key: 'responsibleType', label: 'Quem conduz', type: 'select', required: true, options: options(RESPONSIBLE_LABELS), value: m.responsibleType },
          { key: 'userId', label: 'Advogado interno', type: 'entity', source: this.userLookup, value: m.responsibleUserId, showIf: (v) => v['responsibleType'] === 'INTERNAL' },
          { key: 'lawFirmId', label: 'Escritório parceiro', type: 'select', required: true, options: firms, value: m.lawFirmId, showIf: (v) => v['responsibleType'] === 'LAW_FIRM' },
        ],
        submit: (v) =>
          this.mattersApi.assign(m.id, {
            responsibleType: v['responsibleType'] as 'NONE' | 'INTERNAL' | 'LAW_FIRM',
            userId: (v['userId'] as string) ?? null,
            lawFirmId: (v['lawFirmId'] as string) ?? null,
          }),
      })
      .subscribe(() => this.reload('Responsável atualizado.'));
  }

  // ------------------------------------------------------------------ viabilidade e autorização

  protected recordViability(): void {
    const s = this.sheet()!;
    this.dialog
      .open<ViabilityDialog, ViabilityDialogData>(ViabilityDialog, { data: { matterId: s.matter.id, previous: s.viability }, width: '720px' })
      .afterClosed()
      .subscribe((saved) => saved && this.reload('Análise de viabilidade registrada.'));
  }

  protected requestAuthorization(): void {
    const s = this.sheet()!;
    const recommended = s.viability?.recommendedRoutes ?? [];
    this.forms
      .open({
        title: 'Pedir autorização ao credor',
        message: 'Registra a solicitação com as vias e o orçamento de custas; o caso aguarda a decisão do credor.',
        submitLabel: 'Registrar solicitação',
        details: this.impact(),
        fields: [
          { key: 'routes', label: 'Vias', type: 'multiselect', required: true, options: routeOptions(s.viability?.availableRoutes), value: recommended },
          { key: 'budget', label: 'Orçamento de custas', type: 'money', required: true, min: 0, value: s.viability?.expectedCosts ?? s.matter.expectedCosts },
          { key: 'note', label: 'Observação (opcional)', type: 'textarea', maxLength: 500 },
        ],
        submit: (v) =>
          this.mattersApi.requestAuthorization(s.matter.id, { routes: v['routes'] as never, budget: v['budget'] as number, note: (v['note'] as string) ?? null }).pipe(
            switchMap((a) =>
              s.matter.stage === 'UNDER_REVIEW'
                ? this.mattersApi.transition(s.matter.id, { stage: 'AWAITING_AUTHORIZATION', reason: 'Solicitação de autorização ao credor', closureReason: null, agreementId: null })
                : of(a),
            ),
          ),
      })
      .subscribe(() => this.reload('Autorização solicitada ao credor.'));
  }

  protected decide(a: AuthorizationView): void {
    const s = this.sheet()!;
    const evidence = s.documents.filter((d) => d.kind === 'AUTHORIZATION_EVIDENCE').map((d) => ({ value: d.id, label: `${d.fileName} (${d.uploadedAt.slice(0, 10).split('-').reverse().join('/')})` }));
    this.forms
      .open({
        title: 'Registrar decisão do credor',
        message: evidence.length ? 'A concessão exige evidência (e-mail, documento assinado…) anexada antes em Documentos.' : 'Anexe antes a evidência da autorização em Documentos (tipo "Autorização do credor").',
        submitLabel: 'Registrar decisão',
        details: [
          { label: 'Vias solicitadas', value: this.routeText(a.requestedRoutes) },
          { label: 'Orçamento solicitado', value: formatAmount(a.requestedBudget) },
        ],
        fields: [
          { key: 'decision', label: 'Decisão', type: 'select', required: true, options: [{ value: 'GRANTED', label: 'Concedida' }, { value: 'DENIED', label: 'Negada' }], value: 'GRANTED' },
          { key: 'routes', label: 'Vias autorizadas', type: 'multiselect', required: true, options: routeOptions(a.requestedRoutes), value: a.requestedRoutes, showIf: (v) => v['decision'] === 'GRANTED' },
          { key: 'approvedBudget', label: 'Orçamento aprovado', type: 'money', required: true, min: 0, value: a.requestedBudget, showIf: (v) => v['decision'] === 'GRANTED' },
          { key: 'decisionDate', label: 'Data da decisão', type: 'date', required: true, value: this.today },
          { key: 'channel', label: 'Meio', type: 'select', required: true, options: options(AUTH_CHANNEL_LABELS) },
          { key: 'representative', label: 'Representante do credor', type: 'text', required: true, maxLength: 150 },
          { key: 'evidenceDocumentId', label: 'Evidência', type: 'select', required: true, options: evidence, showIf: (v) => v['decision'] === 'GRANTED' },
          { key: 'note', label: 'Observação (opcional)', type: 'textarea', maxLength: 500 },
        ],
        submit: (v) => this.mattersApi.decideAuthorization(a.id, v as never),
      })
      .subscribe(() => this.reload('Decisão do credor registrada.'));
  }

  // ------------------------------------------------------------------ medidas e processos

  /** Garantias do caso vêm do cálculo de viabilidade (execução extrajudicial exige escolher uma). */
  protected registerMeasure(): void {
    const s = this.sheet()!;
    this.mattersApi
      .previewViability(s.matter.id, { otherAssetsValue: null, realizationRate: null, successProbability: null, expectedCosts: null })
      .pipe(catchError(() => of(null)))
      .subscribe((preview) => {
        const collaterals: FieldOption[] = (preview?.collaterals ?? []).map((c) => ({
          value: c.collateralId,
          label: `${COLLATERAL_TYPE_LABELS[c.type] ?? c.type}${c.estimatedValue ? ' · ' + formatAmount(c.estimatedValue) : ''}`,
        }));
        this.forms
          .open<MeasureView>({
            title: 'Nova medida extrajudicial',
            message: 'A primeira medida leva o caso à fase extrajudicial (exige via extrajudicial autorizada).',
            submitLabel: 'Registrar medida',
            fields: [
              { key: 'type', label: 'Medida', type: 'select', required: true, options: options(MEASURE_TYPE_LABELS) },
              {
                key: 'collateralId',
                label: 'Garantia',
                type: 'select',
                required: true,
                options: collaterals,
                showIf: (v) => v['type'] === 'COLLATERAL_ENFORCEMENT',
                hint: collaterals.length ? undefined : 'Nenhuma garantia exequível encontrada para este caso.',
              },
              { key: 'amount', label: 'Valor', type: 'money', min: 0.01, value: s.matter.debtAmount },
              { key: 'authority', label: 'Cartório, birô ou registro', type: 'text', maxLength: 150 },
              {
                key: 'priorNoticeOn',
                label: 'Comunicação prévia ao devedor em',
                type: 'date',
                required: true,
                showIf: (v) => v['type'] === 'CREDIT_BUREAU_LISTING',
                hint: 'Exigida para negativação (CDC art. 43 §2º).',
              },
              { key: 'dueDate', label: 'Prazo para pagamento', type: 'date' },
            ],
            submit: (v) => this.proceedingsApi.registerMeasure(s.matter.id, { ...v, titleIds: null }),
          })
          .subscribe(() => this.reload('Medida registrada.'));
      });
  }

  protected submitMeasure(m: MeasureView): void {
    this.forms
      .open<MeasureView>({
        title: 'Registrar envio da medida',
        submitLabel: 'Registrar envio',
        fields: [
          { key: 'submittedOn', label: 'Enviada em', type: 'date', required: true, value: this.today },
          { key: 'protocol', label: 'Protocolo (opcional)', type: 'text', maxLength: 80 },
        ],
        submit: (v) => this.proceedingsApi.submitMeasure(m.id, v),
      })
      .subscribe(() => this.reload('Envio registrado.'));
  }

  protected measureNext(m: MeasureView): readonly string[] {
    return (MEASURE_NEXT[m.status] ?? []).filter((s) => s !== 'SUBMITTED');
  }

  protected changeMeasure(m: MeasureView, status: string): void {
    this.forms
      .open<MeasureView>({
        title: `${describeStatus('legalMeasure', status).label}: ${MEASURE_TYPE_LABELS[m.type]}`,
        message: status === 'EFFECTIVE' && m.type === 'PROTEST' ? 'Protesto lavrado interrompe a prescrição: anexe o instrumento em Documentos.' : undefined,
        submitLabel: 'Confirmar',
        fields: [
          { key: 'date', label: 'Data', type: 'date', required: true, value: this.today },
          { key: 'reason', label: 'Motivo / observação', type: 'textarea', maxLength: 500, required: status === 'CANCELLED' || status === 'FAILED' },
        ],
        submit: (v) => this.proceedingsApi.measureStatus(m.id, { status, ...v }),
      })
      .subscribe(() => this.reload('Situação da medida atualizada.'));
  }

  protected registerLawsuit(): void {
    const s = this.sheet()!;
    this.forms
      .open({
        title: 'Cadastrar processo',
        message: 'Exige via judicial autorizada; o caso passa a "ajuizado".',
        submitLabel: 'Cadastrar',
        fields: [
          {
            key: 'cnjNumber',
            label: 'Número CNJ',
            type: 'text',
            required: true,
            maxLength: 30,
            hint: 'NNNNNNN-DD.AAAA.J.TR.OOOO',
            validate: (v) => (cnjValid(String(v)) ? null : 'Número CNJ inválido (confira os dígitos verificadores).'),
          },
          { key: 'route', label: 'Via judicial', type: 'select', required: true, options: routeOptions(this.grantedAuthorization()?.authorizedRoutes.filter((r) => JUDICIAL_ROUTES.includes(r)) ?? JUDICIAL_ROUTES) },
          { key: 'actionClass', label: 'Classe processual (opcional)', type: 'text', maxLength: 150 },
          { key: 'creditorPole', label: 'Polo do credor', type: 'select', required: true, options: [{ value: 'ACTIVE', label: 'Ativo (autor)' }, { value: 'PASSIVE', label: 'Passivo (réu)' }], value: 'ACTIVE' },
          { key: 'claimAmount', label: 'Valor da causa', type: 'money', min: 0.01, value: s.matter.debtAmount },
          { key: 'filedOn', label: 'Distribuído em', type: 'date', required: true },
          { key: 'courtName', label: 'Órgão (opcional)', type: 'text', maxLength: 150 },
          { key: 'courtUnit', label: 'Vara / unidade (opcional)', type: 'text', maxLength: 200 },
        ],
        submit: (v) => this.proceedingsApi.registerLawsuit(s.matter.id, { ...v, debtorIds: null }),
      })
      .subscribe(() => this.reload('Processo cadastrado.'));
  }

  // ------------------------------------------------------------------ prazos

  protected createDeadline(): void {
    const s = this.sheet()!;
    this.forms
      .open<DeadlineView>({
        title: 'Novo prazo',
        message: 'O vencimento é calculado no servidor com feriados, suspensões forenses e recesso (CPC art. 220 e 224).',
        submitLabel: 'Cadastrar prazo',
        fields: [
          { key: 'kind', label: 'Natureza', type: 'select', required: true, options: options(DEADLINE_KIND_LABELS) },
          { key: 'description', label: 'Descrição', type: 'text', required: true, maxLength: 300, wide: true },
          { key: 'lawsuitId', label: 'Processo (opcional)', type: 'select', options: s.lawsuits.map((l) => ({ value: l.id, label: l.cnjFormatted })) },
          { key: 'countingMode', label: 'Contagem', type: 'select', required: true, options: options(COUNTING_LABELS), value: 'BUSINESS_DAYS' },
          { key: 'startDate', label: 'Data do fato inicial', type: 'date', required: true, showIf: (v) => v['countingMode'] !== 'FIXED_DATE' },
          { key: 'startEvent', label: 'Fato inicial (opcional)', type: 'select', options: options(START_EVENT_LABELS), showIf: (v) => v['countingMode'] !== 'FIXED_DATE' },
          { key: 'termDays', label: 'Prazo (dias)', type: 'number', required: true, min: 1, max: 365, showIf: (v) => v['countingMode'] !== 'FIXED_DATE' },
          { key: 'fixedDate', label: 'Vencimento', type: 'date', required: true, showIf: (v) => v['countingMode'] === 'FIXED_DATE' },
          { key: 'responsibleUserId', label: 'Responsável (opcional)', type: 'entity', source: this.userLookup },
        ],
        submit: (v) => this.operationsApi.createDeadline(s.matter.id, { ...v, measureId: null }),
      })
      .subscribe((d) => this.reload(`Prazo cadastrado para ${d.dueDate.split('-').reverse().join('/')}.`));
  }

  protected completeDeadline(d: DeadlineView): void {
    this.forms
      .open<DeadlineView>({
        title: 'Registrar cumprimento',
        message: 'Cumprimento ocorrido até o vencimento corrige um prazo marcado como perdido.',
        submitLabel: 'Registrar',
        details: [
          { label: 'Prazo', value: d.description },
          { label: 'Vencimento', value: d.dueDate.split('-').reverse().join('/') },
        ],
        fields: [
          { key: 'completedOn', label: 'Cumprido em', type: 'date', required: true, value: this.today },
          { key: 'note', label: 'Observação (opcional)', type: 'textarea', maxLength: 500 },
        ],
        submit: (v) => this.operationsApi.completeDeadline(d.id, v['completedOn'] as string, (v['note'] as string) ?? null),
      })
      .subscribe(() => this.reload('Prazo cumprido.'));
  }

  protected cancelDeadline(d: DeadlineView): void {
    this.confirm
      .confirm({ title: 'Cancelar prazo', message: 'O prazo deixa de gerar alertas.', confirmLabel: 'Cancelar prazo', destructive: true, reason: 'required', details: [{ label: 'Prazo', value: d.description }] })
      .subscribe((reason) => this.operationsApi.cancelDeadline(d.id, reason).subscribe(() => this.reload('Prazo cancelado.')));
  }

  // ------------------------------------------------------------------ custas e documentos

  protected recordExpense(): void {
    const s = this.sheet()!;
    const receipts = s.documents.filter((d) => d.kind === 'RECEIPT').map((d) => ({ value: d.id, label: d.fileName }));
    this.forms
      .open<ExpenseView>({
        title: 'Registrar custa ou despesa',
        message: 'Sem autorização vigente ou acima do orçamento aprovado, a despesa pode ser recusada (parâmetro da assessoria).',
        submitLabel: 'Registrar',
        details: this.budgetDetails(),
        fields: [
          { key: 'type', label: 'Tipo', type: 'select', required: true, options: options(EXPENSE_TYPE_LABELS) },
          { key: 'amount', label: 'Valor', type: 'money', required: true, min: 0.01 },
          { key: 'incurredOn', label: 'Data', type: 'date', required: true, value: this.today },
          { key: 'advancedBy', label: 'Quem adiantou', type: 'select', required: true, options: options(ADVANCER_LABELS), value: 'CREDITOR' },
          { key: 'reimbursable', label: 'Reembolsável', type: 'checkbox', value: true },
          { key: 'lawsuitId', label: 'Processo (opcional)', type: 'select', options: s.lawsuits.map((l) => ({ value: l.id, label: l.cnjFormatted })) },
          { key: 'receiptDocumentId', label: 'Comprovante (opcional)', type: 'select', options: receipts, hint: receipts.length ? undefined : 'Anexe o comprovante em Documentos (tipo "Comprovante de despesa").' },
          { key: 'description', label: 'Descrição (opcional)', type: 'text', maxLength: 300, wide: true },
        ],
        submit: (v) => this.operationsApi.recordExpense(s.matter.id, v),
      })
      .subscribe((e) => this.reload(e.overBudget ? 'Despesa registrada acima do orçamento aprovado.' : 'Despesa registrada.'));
  }

  private budgetDetails(): { label: string; value: string }[] {
    const m = this.matter()!;
    return [
      { label: 'Orçamento aprovado', value: m.authorizedBudget !== null ? formatAmount(m.authorizedBudget) : 'Sem autorização vigente' },
      { label: 'Já registrado', value: formatAmount(m.expensesTotal) },
    ];
  }

  protected cancelExpense(e: ExpenseView): void {
    this.confirm
      .confirm({
        title: 'Cancelar despesa',
        message: 'A despesa deixa de contar no total do caso e na prestação de contas.',
        confirmLabel: 'Cancelar despesa',
        destructive: true,
        reason: 'required',
        details: [
          { label: 'Tipo', value: EXPENSE_TYPE_LABELS[e.type] ?? e.type },
          { label: 'Valor', value: formatAmount(e.amount) },
        ],
        warning: 'O cancelamento não pode ser desfeito.',
        confirmationText: 'CANCELAR',
      })
      .subscribe((reason) => this.operationsApi.cancelExpense(e.id, reason).subscribe(() => this.reload('Despesa cancelada.')));
  }

  protected reimburse(e: ExpenseView): void {
    this.forms
      .open<ExpenseView>({
        title: 'Registrar reembolso',
        submitLabel: 'Registrar',
        details: [
          { label: 'Tipo', value: EXPENSE_TYPE_LABELS[e.type] ?? e.type },
          { label: 'Valor', value: formatAmount(e.amount) },
          { label: 'Adiantada por', value: ADVANCER_LABELS[e.advancedBy] ?? e.advancedBy },
        ],
        fields: [
          { key: 'reimbursedOn', label: 'Reembolsada em', type: 'date', required: true, value: this.today },
          { key: 'note', label: 'Observação (opcional)', type: 'textarea', maxLength: 500 },
        ],
        submit: (v) => this.operationsApi.reimburseExpense(e.id, v['reimbursedOn'] as string, (v['note'] as string) ?? null),
      })
      .subscribe(() => this.reload('Reembolso registrado.'));
  }

  protected upload(kind?: string): void {
    const s = this.sheet()!;
    this.forms
      .open<DocumentView>({
        title: 'Anexar documento',
        message: 'PDF, PNG ou JPEG. O tipo do arquivo é conferido pelo conteúdo e o SHA-256 fica registrado.',
        submitLabel: 'Anexar',
        fields: [
          { key: 'kind', label: 'Tipo de documento', type: 'select', required: true, options: options(DOCUMENT_KIND_LABELS), value: kind },
          { key: 'description', label: 'Descrição (opcional)', type: 'text', maxLength: 300, wide: true },
          { key: 'file', label: 'Arquivo', type: 'file', required: true },
        ],
        submit: (v) =>
          this.operationsApi.upload(s.matter.id, { kind: v['kind'] as string, linkType: null, linkId: null, description: (v['description'] as string) ?? null }, v['file'] as File),
      })
      .subscribe(() => this.reload('Documento anexado.'));
  }

  protected download(d: DocumentView): void {
    this.operationsApi.download(d).subscribe();
  }

  protected linkText(d: DocumentView): string {
    return d.linkType ? (DOCUMENT_LINK_LABELS[d.linkType] ?? d.linkType) : 'Caso';
  }

  protected sizeText(bytes: number): string {
    return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }

  protected onLawsuitChanged(): void {
    this.resource.reload();
  }
}
