import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BarChart } from './bar-chart';
import { columnPath, ColumnChart, niceCeil } from './column-chart';
import { FunnelChart } from './funnel-chart';

const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;

describe('gráficos acessíveis', () => {
  it('eixo arredonda o máximo e coluna tem base reta', () => {
    expect(niceCeil(1100)).toBe(2000);
    expect(niceCeil(2400)).toBe(2500);
    expect(niceCeil(0)).toBe(1);
    expect(columnPath(10, 20, 24, 100)).toMatch(/^M10,100V24Q10,20 14,20H30Q34,20 34,24V100Z$/);
    expect(columnPath(10, 100, 24, 100)).toBe('');
  });

  it('barras: legenda com duas séries, valor escrito, rótulo acessível e tabela equivalente', async () => {
    const fixture = TestBed.createComponent(BarChart);
    fixture.componentRef.setInput('title', 'Acordado × pago');
    fixture.componentRef.setInput('summary', 'Resumo do gráfico');
    fixture.componentRef.setInput('series', [
      { name: 'Valor acordado', color: '--app-chart-1' },
      { name: 'Valor pago', color: '--app-chart-2' },
    ]);
    fixture.componentRef.setInput('rows', [{ key: 'HUMAN', label: 'Negociador', values: [1900, 900], display: ['R$ 1.900,00', 'R$ 900,00'], note: '47,4% pago' }]);
    fixture.detectChanges();
    const figure = el(fixture).querySelector('figure')!;
    expect(figure.getAttribute('aria-describedby')).toBeTruthy();
    expect(el(fixture).querySelector('.bc__legend')!.textContent).toContain('Valor pago');
    const bars = el(fixture).querySelectorAll('.bc__bar-line');
    expect(bars).toHaveLength(2);
    expect(bars[1].getAttribute('aria-label')).toBe('Negociador, Valor pago: R$ 900,00 (47,4% pago)');
    expect(bars[0].getAttribute('tabindex')).toBe('0');
    (bars[1] as HTMLElement).dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    expect(el(fixture).querySelector('.bc__tip')!.textContent).toContain('R$ 900,00');
    el(fixture).querySelector<HTMLButtonElement>('.bc__toggle')!.click();
    fixture.detectChanges();
    const table = el(fixture).querySelector('table')!;
    expect(table.textContent).toContain('Negociador');
    expect(table.textContent).toContain('R$ 1.900,00');
    expect(table.textContent).toContain('47,4% pago');
  });

  it('barras vazias mostram estado sem dados', () => {
    const fixture = TestBed.createComponent(BarChart);
    fixture.componentRef.setInput('title', 'x');
    fixture.componentRef.setInput('summary', 'y');
    fixture.componentRef.setInput('series', [{ name: 's', color: '--app-chart-1' }]);
    fixture.componentRef.setInput('rows', []);
    fixture.detectChanges();
    expect(el(fixture).querySelector('[role="status"]')!.textContent).toContain('Sem dados');
  });

  it('colunas por período com rótulo do último valor e tabela', () => {
    const fixture = TestBed.createComponent(ColumnChart);
    fixture.componentRef.setInput('title', 'Recuperado por mês');
    fixture.componentRef.setInput('summary', 'Resumo');
    fixture.componentRef.setInput('series', { name: 'Recuperado líquido', color: '--app-chart-1' });
    fixture.componentRef.setInput('rows', [
      { key: '2026-05', label: 'mai/26', values: [400], display: ['R$ 400,00'] },
      { key: '2026-06', label: 'jun/26', values: [1100], display: ['R$ 1.100,00'] },
    ]);
    fixture.detectChanges();
    const cols = el(fixture).querySelectorAll('g[role="img"]');
    expect(cols).toHaveLength(2);
    expect(cols[1].getAttribute('aria-label')).toBe('jun/26: R$ 1.100,00');
    expect(el(fixture).querySelector('.cc__value')!.textContent).toBe('R$ 1.100,00');
    expect([...el(fixture).querySelectorAll('.cc__tick')].map((t) => t.textContent!.replace(/\s/g, ' '))).toContain('R$ 2 mil');
    el(fixture).querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('table')!.textContent).toContain('mai/26');
  });

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
