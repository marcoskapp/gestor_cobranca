package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.model.ExperimentVariant;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Atribuição aleatória <b>estável</b> de um devedor a um braço do teste A/B: o balde
 * {@code [0, 10000)} vem dos primeiros 8 bytes de SHA-256(sal do experimento + ":" + devedor) — o
 * mesmo devedor cai sempre no mesmo braço, sem depender de ordem de chegada, e devedores diferentes
 * se distribuem uniformemente. Baldes abaixo de {@code controlShare × 100} são controle; os demais
 * são repartidos entre as variantes na proporção dos pesos, na ordem declarada.
 */
public final class VariantAllocator {

    public static final int BUCKETS = 10_000;

    private VariantAllocator() {
    }

    public record Arm(String code, boolean control, int bucket) {
    }

    public static int bucket(String salt, UUID debtorId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + debtorId).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFF);
            }
            return (int) Long.remainderUnsigned(value, BUCKETS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** @param controlShare percentual do grupo de controle (0 a 50) */
    public static Arm allocate(String salt, UUID debtorId, BigDecimal controlShare, List<ExperimentVariant> variants) {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("Experimento sem variantes");
        }
        int bucket = bucket(salt, debtorId);
        int controlBuckets = controlShare.multiply(BigDecimal.valueOf(BUCKETS / 100)).intValue();
        if (bucket < controlBuckets) {
            return new Arm(ExperimentVariant.CONTROL, true, bucket);
        }
        int totalWeight = variants.stream().mapToInt(ExperimentVariant::weight).sum();
        int treated = BUCKETS - controlBuckets;
        // posição do balde dentro da faixa tratada, escalada para os pesos
        long position = (long) (bucket - controlBuckets) * totalWeight / treated;
        int cumulative = 0;
        for (ExperimentVariant v : variants) {
            cumulative += v.weight();
            if (position < cumulative) {
                return new Arm(v.code(), false, bucket);
            }
        }
        return new Arm(variants.getLast().code(), false, bucket);
    }
}
