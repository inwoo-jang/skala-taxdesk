package com.inwoo.taxdesk.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TaxDesk 설정. 세법이 바뀌면 코드가 아니라 이 값들이 바뀐다.
 *
 * <p>특히 {@code brackets} 를 상수가 아니라 설정에 둔 것이 이 실습의 주장이다 —
 * 세법 개정은 배포가 아니라 설정 교체여야 한다. 같은 표가 RAG 문서 04번에도 들어가고,
 * 골든셋이 "문서가 말하는 값"과 "코드가 쓰는 값"의 일치를 검증한다.
 *
 * @param thresholdAmount        종합과세 기준금액 (2,000만원)
 * @param grossUpRate            배당가산율 (11%)
 * @param separateTaxRate        원천징수 세율 (14%)
 * @param localTaxRate           지방소득세율 (결정세액의 10%)
 * @param brackets               누진세율표 — 세법 개정 시 여기만 바꾼다
 * @param users                  고객 계정 (실습용)
 * @param admins                 승인 담당자 계정 (실습용)
 * @param maxInputLength         입력 길이 상한 — 비용 공격 방어
 * @param memoryWindow           기억할 메시지 수
 * @param ragTopK                근거 검색 개수
 * @param ragSimilarityThreshold 근거 검색 임계값
 * @param outOfScopeThreshold    이 점수에도 못 미치면 "상담 범위 밖"으로 본다
 * @param safetyOrder            SafetyAdvisor 의 order — 100 이면 메모리 앞(정상), 250 이면 뒤(실습용)
 * @param routerModel            라우팅 전용 저비용 모델
 * @param fallbackModel          주 모델 장애 시 대체 모델
 * @param docsLocation           RAG 원본 위치
 */
@ConfigurationProperties(prefix = "taxdesk")
public record TaxDeskProperties(long thresholdAmount,
                                double grossUpRate,
                                double separateTaxRate,
                                double localTaxRate,
                                List<Bracket> brackets,
                                Map<String, String> users,
                                Map<String, String> admins,
                                int maxInputLength,
                                int memoryWindow,
                                int ragTopK,
                                double ragSimilarityThreshold,
                                double outOfScopeThreshold,
                                int safetyOrder,
                                String routerModel,
                                String fallbackModel,
                                String docsLocation) {

    /**
     * 누진세율 구간 하나.
     *
     * @param upTo      구간 상한 (원). <b>0 이면 상한 없음</b> — 마지막 구간을 뜻한다.
     *                  설정 파일에 {@code 9223372036854775807} 을 적는 것보다 읽기 낫다.
     * @param rate      세율
     * @param deduction 누진공제액 (원)
     */
    public record Bracket(long upTo, double rate, long deduction) {

        /** 이 구간이 마지막인가 (상한 없음). */
        public boolean isTop() {
            return upTo <= 0;
        }

        /** 실제 비교에 쓸 상한. */
        public long effectiveUpTo() {
            return isTop() ? Long.MAX_VALUE : upTo;
        }
    }

    public TaxDeskProperties {
        thresholdAmount = thresholdAmount <= 0 ? 20_000_000L : thresholdAmount;
        grossUpRate = grossUpRate <= 0 ? 0.11 : grossUpRate;
        separateTaxRate = separateTaxRate <= 0 ? 0.14 : separateTaxRate;
        localTaxRate = localTaxRate <= 0 ? 0.10 : localTaxRate;
        brackets = brackets == null ? List.of() : List.copyOf(brackets);
        users = users == null ? Map.of() : users;
        admins = admins == null ? Map.of() : admins;
        maxInputLength = maxInputLength <= 0 ? 2000 : maxInputLength;
        memoryWindow = memoryWindow <= 0 ? 20 : memoryWindow;
        ragTopK = ragTopK <= 0 ? 4 : ragTopK;
        ragSimilarityThreshold = ragSimilarityThreshold <= 0 ? 0.25 : ragSimilarityThreshold;
        outOfScopeThreshold = outOfScopeThreshold <= 0 ? 0.30 : outOfScopeThreshold;
        safetyOrder = safetyOrder == 0 ? 100 : safetyOrder;
        docsLocation = docsLocation == null ? "classpath:/tax-docs/" : docsLocation;
    }
}
