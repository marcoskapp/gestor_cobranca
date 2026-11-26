import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FunnelChart } from './funnel-chart';

const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;

describe('funil acessível', () => {
  it('funil escreve a conversão entre as etapas', () => {
    const fixture = TestBed.createComponent(FunnelChart);
    fixture.componentRef.setInput('title', 'Funil');
    fixture.componentRef.setInput('summary', '3 contatos');
    fixture.componentRef.setInput('stages', [
      { label: 'Contatos efetivos', value: 3, display: '3' },
      { label: 'Propostas', value: 4, display: '4', conversion: '133,3% · 4 de 3' },
    ]);
    fixture.detectChanges();
    const stages = el(fixture).querySelectorAll('.fn__stage');
    expect(stages[1].getAttribute('aria-label')).toBe('Propostas: 4, conversão 133,3% · 4 de 3');
    expect(el(fixture).querySelector('.fn__conv')!.textContent).toContain('4 de 3');
  });
});
