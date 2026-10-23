import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { TONE_ICONS } from '../../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { OriginContractsApi } from '../../data/debt.api';
import { INSTRUMENT_TYPE_LABELS, INTERRUPTION_TYPE_LABELS, PRESCRIPTION_CLAIM_LABELS, prescriptionTone } from '../../data/debt.labels';
import { PrescriptionClaimView, PrescriptionInterruptionView } from '../../data/debt.models';

/**
 * Prescrição estimada do título por pretensão (cobrança e, para título executivo, execução) e as
 * interrupções registradas. Estimativa operacional: a regra precisa estar validada pelo jurídico.
 */
@Component({
  selector: 'app-title-prescription',
  imports: [MatIconModule, DataTable, CellDef, AppDatePipe],
  template: `
    @if (view(); as p) {
      <p class="app-muted">
        {{ p.instrumentType ? instruments[p.instrumentType] : 'Natureza do contrato não classificada' }}{{ p.executiveTitle ? ' · título executivo' : '' }}
      </p>
      @if (unvalidated()) {
        <p class="app-alert app-alert--warning" role="note">
          <mat-icon aria-hidden="true">gavel</mat-icon>
          <span>A regra de prescrição ainda não foi validada pelo jurídico: use as datas como referência, não como parecer.</span>
        </p>
      }
    }
    <app-data-table
      caption="Prescrição por pretensão"
      [rows]="claims()"
      [columns]="claimColumns"
      [loading]="prescription.isLoading()"
      [failed]="prescription.status() === 'error'"
      (retry)="prescription.reload()"
      [rowKey]="claimKey"
      emptyIcon="hourglass_empty"
      emptyMessage="Sem regra de prescrição aplicável a este título."
    >
      <ng-template appCell="claim" [appCellOf]="claims()" let-c>
        {{ claimLabels[c.claim] ?? c.claim }}
        @if (c.legalBasis) {
          <div class="app-muted">{{ c.legalBasis }}</div>
        }
      </ng-template>
      <ng-template appCell="counting" [appCellOf]="claims()" let-c>
        {{ c.countingStart | appDate: 'date' }}
        <div class="app-muted">{{ c.termMonths }} meses</div>
      </ng-template>
      <ng-template appCell="estimatedDate" [appCellOf]="claims()" let-c>{{ c.estimatedDate | appDate: 'date' }}</ng-template>
      <ng-template appCell="situation" [appCellOf]="claims()" let-c>
        @let tone = toneOf(c);
        <span class="app-status" [class]="'app-status--' + tone"
          ><mat-icon class="app-status__icon" aria-hidden="true">{{ icons[tone] }}</mat-icon>{{ situationText(c) }}</span
        >
      </ng-template>
    </app-data-table>

    @if (interruptions().length) {
      <h3 class="app-section-title tp-gap">Interrupções registradas</h3>
      <app-data-table caption="Interrupções da prescrição" [rows]="interruptions()" [columns]="interruptionColumns">
        <ng-template appCell="date" [appCellOf]="interruptions()" let-i>{{ i.date | appDate: 'date' }}</ng-template>
        <ng-template appCell="type" [appCellOf]="interruptions()" let-i>
          {{ interruptionLabels[i.type] ?? i.type }}
          <div class="app-muted">{{ i.origin === 'LEGAL' ? 'Registrada pelo jurídico' : 'Registro manual' }}</div>
        </ng-template>
        <ng-template appCell="reference" [appCellOf]="interruptions()" let-i>{{ i.reference || '—' }}</ng-template>
        <ng-template appCell="effectiveFor" [appCellOf]="interruptions()" let-i>{{ effectText(i) }}</ng-template>
      </app-data-table>
    }
  `,
  styles: `
    .tp-gap {
      margin-top: 24px;
    }
  `,
})
export class TitlePrescription {
  readonly titleId = input.required<string>();
  private readonly api = inject(OriginContractsApi);

  protected readonly instruments = INSTRUMENT_TYPE_LABELS;
  protected readonly claimLabels = PRESCRIPTION_CLAIM_LABELS;
  protected readonly interruptionLabels = INTERRUPTION_TYPE_LABELS;
  protected readonly icons = TONE_ICONS;

  protected readonly prescription = rxResource({ params: () => this.titleId(), stream: ({ params }) => this.api.prescription(params) });
  protected readonly view = valueOr(this.prescription, undefined);
  protected readonly claims = computed(() => this.view()?.claims ?? []);
  protected readonly interruptions = computed(() => this.view()?.interruptions ?? []);
  protected readonly unvalidated = computed(() => this.claims().some((c) => !c.ruleValidated));
  protected readonly claimKey = (c: PrescriptionClaimView) => c.claim;

  protected readonly claimColumns: TableColumn[] = [
    { key: 'claim', header: 'Pretensão' },
    { key: 'counting', header: 'Início da contagem' },
    { key: 'estimatedDate', header: 'Data estimada' },
    { key: 'situation', header: 'Situação' },
  ];

  protected readonly interruptionColumns: TableColumn[] = [
    { key: 'date', header: 'Data' },
    { key: 'type', header: 'Causa' },
    { key: 'reference', header: 'Referência' },
    { key: 'effectiveFor', header: 'Reiniciou a contagem de' },
  ];

  protected toneOf(c: PrescriptionClaimView): 'danger' | 'warning' | 'neutral' {
    return prescriptionTone(c);
  }

  protected situationText(c: PrescriptionClaimView): string {
    if (c.situation === 'PRESCRIBED' || c.daysRemaining < 0) {
      return 'Prescrita (estimativa)';
    }
    return c.daysRemaining === 1 ? 'Falta 1 dia' : `Faltam ${c.daysRemaining} dias`;
  }

  protected effectText(i: PrescriptionInterruptionView): string {
    return i.effectiveFor.length ? i.effectiveFor.map((c) => PRESCRIPTION_CLAIM_LABELS[c] ?? c).join(', ') : 'Sem efeito no cálculo vigente';
  }
}
