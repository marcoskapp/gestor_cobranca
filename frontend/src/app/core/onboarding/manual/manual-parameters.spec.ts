import { CHECKLISTS } from '../content/checklists';
import { RELEASE_NOTES } from '../content/release-notes';
import { SCREENS } from '../content/screens';
import { TOURS } from '../content/tours';
import { loadAllIndicators } from './indicators/indicator-index';
import { loadKnowledgeBase } from './manual-index';
import {
  MANUAL_PARAMETERS,
  MANUAL_PHRASES,
  ManualParameterKey,
  ManualParameterValues,
  ManualPhraseKey,
  parameterValue,
  renderPhrase,
  resolveManualText,
  unknownMarkers,
} from './manual-parameters';
import { jargonIn } from './content-rules';

/** Valores de exemplo (os padrões da configuração), para conferir as frases e o tamanho dos textos. */
const DEFAULTS: Record<ManualParameterKey, number> = {
  'debt.cumulativeWindowDays': 30,
  'debt.proposalValidityDays': 15,
  'negotiation.breakSuspensionMaxDays': 7,
  'negotiation.breakSuspensionGraceDays': 3,
  'payment.rejectedReviewWindowDays': 30,
  'compliance.dataSubjectRequestDueDays': 15,
  'pendingwork.dueSoonDays': 3,
  'telephony.presenceTimeoutSeconds': 90,
  'ingestion.pdfMaxFileSizeMb': 20,
  'scoring.outcomeDays': 30,
  'scoring.outcomeGraceDays': 5,
  'scoring.minAuc': 0.6,
  'scoring.psiWarning': 0.1,
  'scoring.psiAlert': 0.25,
  'settlement.adjustmentDualApprovalThreshold': 1000,
};

/** Texto com marcador onde ele é resolvido (pipe `manualText`) e onde não pode aparecer (títulos, nomes, notas). */
async function contentTexts(): Promise<{ resolved: [string, string][]; plain: [string, string][] }> {
  const [kb, indicators] = await Promise.all([loadKnowledgeBase(), loadAllIndicators()]);
  const resolved: [string, string][] = [];
  const plain: [string, string][] = [];
  for (const { id, manual } of kb.modules) {
    [...manual.overview, ...manual.rules, ...manual.connections.map((c) => c.text)].forEach((t) => resolved.push([id, t]));
    for (const s of manual.screens) {
      [s.objective, ...s.purpose, ...s.inputs, ...s.outputs, ...s.related.map((r) => r.why)].forEach((t) => resolved.push([s.id, t]));
      plain.push([s.id, s.title]);
    }
  }
  for (const t of kb.glossary) {
    resolved.push([t.id, t.definition]);
    plain.push([t.id, t.term]);
  }
  for (const i of indicators) {
    [i.what, i.how, i.use].forEach((t) => resolved.push([i.id, t]));
    plain.push([i.id, i.title]);
  }
  for (const tour of TOURS) {
    plain.push([tour.id, tour.title], [tour.id, tour.summary]);
    for (const step of tour.steps) {
      resolved.push([tour.id, step.body]);
      plain.push([tour.id, step.title]);
    }
  }
  for (const screen of SCREENS) {
    (screen.changes ?? []).flatMap((c) => c.items).forEach((t) => resolved.push([screen.key, t]));
  }
  RELEASE_NOTES.forEach((n) => [n.title, ...n.items.map((i) => i.text)].forEach((t) => plain.push([`novidade ${n.version}`, t])));
  CHECKLISTS.forEach((c) => c.items.forEach((i) => plain.push([`${c.id}/${i.id}`, i.label], [`${c.id}/${i.id}`, i.description])));
  return { resolved, plain };
}

describe('parâmetros do manual (marcadores resolvidos com o valor do servidor)', () => {
  it('resolve o marcador com o valor e usa as formas de singular e zero', () => {
    const values: ManualParameterValues = { ...DEFAULTS };
    expect(resolveManualText('Somando as do título {{debt.cumulativeWindow}}; vence {{debt.proposalValidity}}.', values)).toBe(
      'Somando as do título nos últimos 30 dias; vence em 15 dias.',
    );
    expect(renderPhrase('debt.cumulativeWindow', { 'debt.cumulativeWindowDays': 1 })).toBe('no último dia');
    expect(renderPhrase('negotiation.breakSuspensionGrace', { 'negotiation.breakSuspensionGraceDays': 0 })).toBe('no mesmo dia');
    expect(renderPhrase('scoring.minAuc', values)).toBe('padrão 0,60');
    expect(renderPhrase('settlement.adjustmentDualApproval', { 'settlement.adjustmentDualApprovalThreshold': 1234567.5 })).toBe(
      'acima de R$ 1.234.567,50',
    );
    expect(resolveManualText('Sem marcador.', values)).toBe('Sem marcador.');
  });

  it('decimal com as casas que o valor tem (2 a 4), sem arredondar, igual ao backend; reais com 2', () => {
    const auc = (v: number) => renderPhrase('scoring.minAuc', { 'scoring.minAuc': v });
    expect(auc(0.625)).toBe('padrão 0,625');
    expect(auc(0.6)).toBe('padrão 0,60');
    expect(auc(0.615)).toBe('padrão 0,615');
    expect(auc(0.1234)).toBe('padrão 0,1234');
    expect(auc(10)).toBe('padrão 10,00');
    // mais de 4 casas: texto neutro, nunca número cortado
    expect(auc(0.12345)).toBe('definido nos parâmetros');
    expect(renderPhrase('settlement.adjustmentDualApproval', { 'settlement.adjustmentDualApprovalThreshold': 1000 })).toBe('acima de R$ 1.000,00');
  });

  it('valor de parâmetro para regras de tela: só o que serve ao formato', () => {
    expect(parameterValue('ingestion.pdfMaxFileSizeMb', { 'ingestion.pdfMaxFileSizeMb': 20 })).toBe(20);
    expect(parameterValue('ingestion.pdfMaxFileSizeMb', { 'ingestion.pdfMaxFileSizeMb': 20.5 })).toBeNull();
    expect(parameterValue('ingestion.pdfMaxFileSizeMb', null)).toBeNull();
    expect(parameterValue('settlement.adjustmentDualApprovalThreshold', { 'settlement.adjustmentDualApprovalThreshold': 1000.5 })).toBe(1000.5);
  });

  it('sem valor (carregando ou falha) ou com valor fora do formato, usa o texto neutro — nunca um número chutado', () => {
    expect(resolveManualText('Somando {{debt.cumulativeWindow}}.', null)).toBe('Somando no período configurado.');
    expect(renderPhrase('debt.cumulativeWindow', {})).toBe('no período configurado');
    expect(renderPhrase('debt.cumulativeWindow', { 'debt.cumulativeWindowDays': 1.5 })).toBe('no período configurado');
    expect(renderPhrase('debt.cumulativeWindow', { 'debt.cumulativeWindowDays': -1 })).toBe('no período configurado');
    expect(renderPhrase('debt.cumulativeWindow', { 'debt.cumulativeWindowDays': Number.NaN })).toBe('no período configurado');
    for (const key of Object.keys(MANUAL_PHRASES) as ManualPhraseKey[]) {
      expect(renderPhrase(key, null), key).not.toMatch(/\d/);
    }
  });

  it('marcador desconhecido ou mal formado é apontado (e fica como está na tela)', () => {
    expect(unknownMarkers('ok {{debt.cumulativeWindow}}')).toEqual([]);
    expect(unknownMarkers('erro {{debt.naoExiste}}')).toEqual(['{{debt.naoExiste}}']);
    expect(unknownMarkers('erro {{ debt.cumulativeWindow }}')).toHaveLength(1);
    expect(unknownMarkers('erro {{debt.cumulativeWindow}')).toHaveLength(1);
    expect(resolveManualText('erro {{debt.naoExiste}}', DEFAULTS)).toBe('erro {{debt.naoExiste}}');
  });

  it('catálogo coerente: toda frase aponta para um parâmetro, tem {n} e formas sem número; todo parâmetro é usado', () => {
    const used = new Set<string>();
    for (const [key, phrase] of Object.entries(MANUAL_PHRASES)) {
      expect(key).toMatch(/^[a-z]+\.[a-zA-Z0-9]+$/);
      expect(MANUAL_PARAMETERS[phrase.parameter], key).toBeDefined();
      expect(phrase.other, key).toContain('{n}');
      expect(phrase.neutral, key).not.toMatch(/\d|\{n\}/);
      for (const form of ['zero', 'one'] as const) {
        const text = (phrase as { zero?: string; one?: string })[form];
        if (text) {
          expect(MANUAL_PARAMETERS[phrase.parameter], `${key}.${form} só em inteiros`).toBe('integer');
          expect(text, key).not.toContain('{n}');
        }
      }
      used.add(phrase.parameter);
    }
    expect([...used].sort()).toEqual(Object.keys(MANUAL_PARAMETERS).sort());
  });

  it('todo marcador do conteúdo é conhecido e só aparece onde é resolvido', async () => {
    const { resolved, plain } = await contentTexts();
    for (const [where, text] of resolved) {
      expect(unknownMarkers(text), `${where}: "${text}"`).toEqual([]);
    }
    for (const [where, text] of plain) {
      expect(text, `${where}: marcador onde o texto não é resolvido`).not.toContain('{{');
    }
    // os prazos da correção de valor não estão mais escritos à mão
    expect(resolved.filter(([, t]) => t.includes('{{debt.')).length).toBeGreaterThanOrEqual(6);
  });

  it('resolvido com os padrões ou com o texto neutro, o conteúdo segue as regras de tamanho e de linguagem', async () => {
    const words = (text: string) => text.trim().split(/\s+/).length;
    for (const tour of TOURS) {
      for (const step of tour.steps) {
        for (const values of [DEFAULTS, null]) {
          expect(words(resolveManualText(step.body, values)), `${tour.id}: ${step.title}`).toBeLessThanOrEqual(25);
        }
      }
    }
    for (const i of await loadAllIndicators()) {
      for (const text of [i.what, i.how, i.use]) {
        for (const values of [DEFAULTS, null]) {
          const resolvedText = resolveManualText(text, values);
          expect(words(resolvedText), `${i.id}: até 70 palavras`).toBeLessThanOrEqual(70);
          expect(jargonIn(resolvedText), `${i.id}: "${resolvedText}"`).toBeNull();
        }
      }
    }
  });
});
