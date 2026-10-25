import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow, addDays } from '../../collection/data/collection.rules';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { IndexesApi, PoliciesApi } from '../data/negotiation.api';
import { OVERRIDE_GROUP_LABELS, overrideScope } from '../data/negotiation.labels';
import { PolicyOverride, PolicyRules, PolicyTerms, PolicyView } from '../data/negotiation.models';
import { cloneTerms, overriddenGroups, specificity, termsProblems } from '../data/negotiation.rules';
import { DateDialog, DateDialogData, OverrideDialog, OverrideDialogData } from './policy-dialogs';
import { RulesEditor } from './rules-editor';

/**
 * Versão de política: leitura (publicada/aposentada) ou edição do rascunho com validação contínua
 * dos termos, sobreposições por carteira/faixa de atraso, publicação com data de vigência, nova
 * versão e aposentadoria. A linha do tempo mostra todas as versões do credor.
 */
@Component({
  selector: 'app-policy-detail',
  imports: [Icon, NgbTooltip, ...FORM_FIELD, FormsModule, RouterLink, EmptyState, PageHeader, Skeleton, StatusChip, AppDatePipe, RulesEditor],
  templateUrl: './policy-detail.html',
  styleUrl: './policy-detail.scss',
})
export class PolicyDetail {
  readonly id = input.required<string>();

  private readonly api = inject(PoliciesApi);
  private readonly indexesApi = inject(IndexesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly groupLabels = OVERRIDE_GROUP_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.POLICY_MANAGE));

  protected readonly policy = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.policy, undefined);
  private readonly versionList = rxResource({
    params: () => this.current()?.creditorId,
    stream: ({ params }) => this.api.search({ creditorId: params }, { page: 0, size: 100 }),
  });
  protected readonly versions = computed(() =>
    this.versionList.hasValue() ? [...this.versionList.value()!.content].sort((a, b) => b.version - a.version) : [],
  );
  private readonly indexList = rxResource({ stream: () => this.indexesApi.list() });
  protected readonly indexes = valueOr(this.indexList, []);

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.draft()?.overrides.map((o) => o.portfolioId) ?? []);
  /** Quem publicou cada versão (lookup leve de nomes; sem e-mail). */
  protected readonly publishers = nameMap(inject(UserNames), () => [this.current()?.publishedBy], { batch: USER_NAMES_BATCH });

  /** Termos em edição (cópia; a versão do servidor só muda ao salvar). */
  protected readonly draft = signal<PolicyTerms | null>(null);
  protected readonly name = signal('');
  protected readonly description = signal('');
  private readonly revision = signal(0);
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  /** Índice da sobreposição aberta no editor (-1 = regras base). */
  protected readonly selectedOverride = signal(-1);

  protected readonly editable = computed(() => this.current()?.status === 'DRAFT' && this.canManage());
  protected readonly problems = computed(() => {
    this.revision();
    const d = this.draft();
    return d ? termsProblems(d) : [];
  });
  protected readonly hasDraft = computed(() => this.versions().some((v) => v.status === 'DRAFT'));

  /** Regras exibidas no editor: base ou a combinação da sobreposição (só os grupos substituídos aparecem). */
  protected readonly editorRules = computed<PolicyRules | null>(() => {
    this.revision();
    const d = this.draft();
    if (!d) {
      return null;
    }
    const o = d.overrides[this.selectedOverride()];
    if (!o) {
      return d.base;
    }
    return {
      charges: o.charges ?? d.base.charges,
      discountTiers: o.discountTiers ?? d.base.discountTiers,
      installments: o.installments ?? d.base.installments,
      validity: o.validity ?? d.base.validity,
      ladder: o.ladder ?? d.base.ladder,
      breakRule: o.breakRule ?? d.base.breakRule,
    };
  });
  protected readonly editorGroups = computed(() => {
    this.revision();
    const o = this.draft()?.overrides[this.selectedOverride()];
    return o ? overriddenGroups(o) : (['charges', 'discountTiers', 'installments', 'validity', 'ladder', 'breakRule'] as const);
  });

  constructor() {
    effect(() => {
      const p = this.current();
      this.breadcrumbs.setCurrentLabel(p ? `v${p.version} · ${p.name}` : null);
      if (p) {
        this.draft.set(cloneTerms(p.terms));
        this.name.set(p.name);
        this.description.set(p.description ?? '');
        this.dirty.set(false);
        this.selectedOverride.set(-1);
        this.revision.update((n) => n + 1);
      }
    });
  }

  protected edited(): void {
    this.dirty.set(true);
    this.revision.update((n) => n + 1);
  }

  protected scope(o: PolicyOverride): string {
    return overrideScope(o, (id) => this.portfolios.name(id));
  }

  protected groupsText(o: PolicyOverride): string {
    return overriddenGroups(o)
      .map((g) => OVERRIDE_GROUP_LABELS[g])
      .join(', ');
  }

  protected specificityText(o: PolicyOverride): string {
    return ['', 'faixa', 'carteira', 'carteira + faixa'][specificity(o)];
  }

  protected editOverride(index: number | null): void {
    const d = this.draft();
    const p = this.current();
    if (!d || !p) {
      return;
    }
    this.dialog
      .open<OverrideDialog, OverrideDialogData, PolicyOverride>(OverrideDialog, {
        data: { creditorId: p.creditorId, base: d.base, override: index === null ? null : d.overrides[index] },
      })
      .afterClosed()
      .subscribe((o) => {
        if (!o) {
          return;
        }
        if (index === null) {
          d.overrides.push(o);
          this.selectedOverride.set(d.overrides.length - 1);
        } else {
          d.overrides[index] = o;
        }
        this.edited();
      });
  }

  protected removeOverride(index: number): void {
    const d = this.draft();
    if (!d) {
      return;
    }
    this.confirm
      .confirm({ title: 'Remover sobreposição', message: 'As regras base passam a valer para essa carteira/faixa.', confirmLabel: 'Remover', destructive: true, details: [{ label: 'Sobreposição', value: d.overrides[index].code }] })
      .subscribe(() => {
        d.overrides.splice(index, 1);
        this.selectedOverride.set(-1);
        this.edited();
      });
  }

  protected applyTemplate(): void {
    this.confirm
      .confirm({
        title: 'Aplicar modelo de mercado',
        message: 'Os termos do rascunho são substituídos pelos parâmetros usuais de mercado (as sobreposições são removidas). Nada é salvo até você salvar o rascunho.',
        confirmLabel: 'Aplicar modelo',
      })
      .subscribe(() =>
        this.api.template().subscribe((terms) => {
          this.draft.set(terms);
          this.selectedOverride.set(-1);
          this.edited();
        }),
      );
  }

  protected save(): void {
    const p = this.current();
    const d = this.draft();
    if (!p || !d || this.saving()) {
      return;
    }
    if (this.problems().length || !this.name().trim()) {
      this.notifications.error('Corrija os problemas indicados antes de salvar.');
      return;
    }
    this.saving.set(true);
    this.api
      .update(p.id, { name: this.name().trim(), description: this.description().trim() || null, terms: d })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((saved) => {
        this.policy.set(saved);
        this.notifications.success('Rascunho salvo.');
      });
  }

  protected publish(): void {
    const p = this.current();
    if (!p) {
      return;
    }
    if (this.dirty()) {
      this.notifications.error('Salve o rascunho antes de publicar.');
      return;
    }
    const today = zonedNow().date;
    const active = this.versions().find((v) => v.status === 'PUBLISHED' && (!v.effectiveTo || v.effectiveTo >= today));
    this.dialog
      .open<DateDialog, DateDialogData, string | null>(DateDialog, {
        data: {
          title: `Publicar versão ${p.version}`,
          message: 'Depois de publicada, a versão não pode mais ser alterada. Propostas e simulações passam a usá-la a partir da data de vigência.',
          label: 'Vigente a partir de',
          confirmLabel: 'Publicar',
          min: today,
          initial: today,
          details: [
            { label: 'Credor', value: this.creditors.name(p.creditorId) },
            { label: 'Versão', value: `v${p.version} · ${p.name}` },
            { label: 'Sobreposições', value: String(p.terms.overrides.length) },
            { label: 'Versão atual', value: active ? `v${active.version} (vigência encerrada no dia anterior)` : 'nenhuma' },
          ],
          warning: 'A publicação não pode ser retroativa.',
        },
      })
      .afterClosed()
      .subscribe((date) => {
        if (date) {
          this.api.publish(p.id, date).subscribe((saved) => {
            this.policy.set(saved);
            this.versionList.reload();
            this.notifications.success(`Versão ${saved.version} publicada a partir de ${date.split('-').reverse().join('/')}.`);
          });
        }
      });
  }

  protected retire(): void {
    const p = this.current();
    if (!p) {
      return;
    }
    const today = zonedNow().date;
    this.dialog
      .open<DateDialog, DateDialogData, string | null>(DateDialog, {
        data: {
          title: `Aposentar versão ${p.version}`,
          message: 'A vigência termina na data escolhida (inclusive). Sem outra versão vigente, o credor não pode ser negociado.',
          label: 'Último dia de vigência',
          confirmLabel: 'Aposentar',
          min: addDays(today, -1),
          initial: today,
          details: [
            { label: 'Credor', value: this.creditors.name(p.creditorId) },
            { label: 'Versão', value: `v${p.version} · ${p.name}` },
          ],
          warning: 'Ontem = retirada imediata.',
        },
      })
      .afterClosed()
      .subscribe((date) => {
        if (date) {
          this.api.retire(p.id, date).subscribe((saved) => {
            this.policy.set(saved);
            this.versionList.reload();
            this.notifications.success(`Versão ${saved.version} aposentada.`);
          });
        }
      });
  }

  protected newVersion(): void {
    const p = this.current();
    if (!p) {
      return;
    }
    this.api.newVersion(p.id).subscribe((created) => {
      this.notifications.success(`Rascunho da versão ${created.version} criado a partir da versão ${p.version}.`);
      void this.router.navigate(['/negociacao/politicas', created.id]);
    });
  }

  protected discard(): void {
    const p = this.current();
    if (p) {
      this.draft.set(cloneTerms(p.terms));
      this.name.set(p.name);
      this.description.set(p.description ?? '');
      this.dirty.set(false);
      this.selectedOverride.set(-1);
      this.revision.update((n) => n + 1);
    }
  }

  protected versionView(v: PolicyView): string {
    return v.effectiveFrom ? `${v.effectiveFrom.split('-').reverse().join('/')} a ${v.effectiveTo ? v.effectiveTo.split('-').reverse().join('/') : 'em aberto'}` : 'não publicada';
  }
}
