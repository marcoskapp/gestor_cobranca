import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { KpiCard } from './kpi-card';

@Component({
  imports: [KpiCard],
  template: `
    <app-kpi-card label="Saldo em aberto" record detail="3 títulos">R$ 10,00</app-kpi-card>
    <app-kpi-card label="Prazos perdidos" record tone="danger">2</app-kpi-card>
    <app-kpi-card label="Líquido a repassar" record emphasis>R$ 5,00</app-kpi-card>
    <app-kpi-card label="Forma" record variant="text">À vista</app-kpi-card>
  `,
})
class Host {}

describe('KpiCard', () => {
  function render(): HTMLElement[] {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('app-kpi-card')];
  }

  it('grupo rotulado com rótulo em uma linha (texto completo no title), valor e detalhe', () => {
    const [card] = render();
    expect(card.getAttribute('role')).toBe('group');
    expect(card.getAttribute('aria-label')).toBe('Saldo em aberto');
    expect(card.querySelector('.app-kpi__label')?.getAttribute('title')).toBe('Saldo em aberto');
    expect(card.querySelector('.app-kpi__value')?.textContent?.trim()).toBe('R$ 10,00');
    expect(card.querySelector('.app-kpi__detail')?.textContent).toBe('3 títulos');
    // valor do registro: sem informação contextual
    expect(card.querySelector('app-info-hint')).toBeNull();
  });

  it('tom, destaque do total e valor textual viram classes do cartão', () => {
    const [, late, total, text] = render();
    expect(late.classList).toContain('app-kpi--danger');
    expect(total.classList).toContain('app-kpi--emphasis');
    expect(text.classList).toContain('app-kpi--text');
    expect(late.querySelector('.app-kpi__detail')).toBeNull();
  });
});
