import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApprovalDecision, ApprovalParty, ApprovalReason } from './approval-decision';
import { QueuePosition, pick, positionText } from './approval-queue';

@Component({
  imports: [ApprovalDecision],
  template: `
    <app-approval-decision
      [queueLink]="queueLink()"
      [queueParams]="{ credor: 'c-1' }"
      itemLabel="Pagamento"
      [position]="position()"
      [hasNext]="hasNext()"
      [reasons]="reasons"
      [requester]="requester"
      [approver]="approver"
      [canDecide]="canDecide()"
      [blockedReason]="blocked()"
      [approveBlockedReason]="approveBlocked()"
      (approve)="approved = approved + 1"
      (reject)="rejected = rejected + 1"
    />
  `,
})
class Host {
  readonly queueLink = signal<string | null>('/pagamentos/aprovacoes');
  readonly position = signal<QueuePosition | null>({ index: 1, total: 4 });
  readonly hasNext = signal(true);
  readonly canDecide = signal(true);
  readonly blocked = signal<string | null>(null);
  readonly approveBlocked = signal<string | null>(null);
  readonly reasons: ApprovalReason[] = [{ label: 'Valor do pagamento', requested: 'R$ 900,00', limit: 'R$ 500,00', excess: '+R$ 400,00 (80% acima)' }];
  readonly requester: ApprovalParty = { name: 'Carla', role: 'informou o pagamento' };
  readonly approver: ApprovalParty = { name: 'Você' };
  approved = 0;
  rejected = 0;
}

describe('ApprovalDecision', () => {
  let fixture: ComponentFixture<Host>;
  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const buttons = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')];

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideRouter([])] });
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
  });

  it('contador na fila, quanto passa, quem pediu × quem decide e "… e abrir a próxima"', () => {
    expect(text()).toContain('Pagamento 2 de 4');
    expect(text()).toContain('+R$ 400,00 (80% acima)');
    expect(text()).toContain('Carla · informou o pagamento');
    const link = (fixture.nativeElement as HTMLElement).querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/pagamentos/aprovacoes?credor=c-1');
    expect(buttons().map((b) => b.textContent?.trim())).toEqual(['Reprovar e abrir a próxima', 'Aprovar e abrir a próxima']);
    buttons()[1].click();
    buttons()[0].click();
    expect(fixture.componentInstance.approved).toBe(1);
    expect(fixture.componentInstance.rejected).toBe(1);
  });

  it('último da fila volta à fila; fora da fila, sem barra e sem complemento', () => {
    fixture.componentInstance.hasNext.set(false);
    fixture.detectChanges();
    expect(buttons()[1].textContent?.trim()).toBe('Aprovar e voltar à fila');
    fixture.componentInstance.queueLink.set(null);
    fixture.detectChanges();
    expect(text()).not.toContain('2 de 4');
    expect(buttons()[1].textContent?.trim()).toBe('Aprovar');
  });

  it('sem poder decidir este item: explica e as ações somem', () => {
    fixture.componentInstance.blocked.set('Você informou este pagamento.');
    fixture.detectChanges();
    expect(text()).toContain('Você informou este pagamento.');
    expect(buttons()).toHaveLength(0);
    fixture.componentInstance.blocked.set(null);
    fixture.componentInstance.canDecide.set(false);
    fixture.detectChanges();
    expect(buttons()).toHaveLength(0);
  });

  it('alçada insuficiente: reprovar continua disponível, só "Aprovar" some com o motivo', () => {
    fixture.componentInstance.approveBlocked.set('As condições passam também da sua alçada.');
    fixture.detectChanges();
    expect(text()).toContain('As condições passam também da sua alçada.');
    expect(buttons().map((b) => b.textContent?.trim())).toEqual(['Reprovar e abrir a próxima']);
  });

  it('funções puras da fila', () => {
    expect(positionText({ index: 0, total: 3 })).toBe('1 de 3');
    expect(positionText(null)).toBeNull();
    const params: Record<string, string | null> = { credor: 'c-1', origem: '', pagina: '2', outro: 'x' };
    expect(pick(Object.keys(params), (k) => params[k], new Set(['credor', 'origem']))).toEqual({ credor: 'c-1' });
  });
});
