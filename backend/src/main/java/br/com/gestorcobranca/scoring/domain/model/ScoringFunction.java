package br.com.gestorcobranca.scoring.domain.model;

/** Definição imutável de um modelo capaz de escorar um vetor de features. */
public interface ScoringFunction {

    Evaluation evaluate(FeatureVector features);
}
