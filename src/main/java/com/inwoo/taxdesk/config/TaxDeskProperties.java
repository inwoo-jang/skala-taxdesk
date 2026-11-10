package com.inwoo.taxdesk.config;

import java.util.ArrayList;
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
 * <p><b>규칙에는 버전이 붙는다.</b> {@code taxYear} · {@code ruleVersion} · {@code ruleVerifiedOn} 은
 * "이 숫자가 어느 법령의 어느 시행본을 언제 대조한 값인가"를 응답에 실어 보내기 위한 것이다.
 * 버전 정보가 비어 있으면 {@link #problems()} 가 잡고, 서비스는 판정을 보류한다 —
 * 어느 해 기준인지 모르는 세율표로 세액을 내보내는 것보다 멈추는 쪽이 낫다.
 *
 * @param taxYear                귀속연도. 계좌 조회 · 근거 검색 필터 · 안내 문구가 전부 이 값을 쓴다
 * @param ruleVersion            적용 법령 버전 (예: 소득세법 법률 제21221호, 시행 2026-07-01)
 * @param ruleVerifiedOn         설정값을 원문과 대조한 날짜 (ISO)
 * @param thresholdAmount        종합과세 기준금액 (2,000만원)
 * @param grossUpRate            배당가산율 (10%)
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
public record TaxDeskProperties(int taxYear,
                                String ruleVersion,
                                String ruleVerifiedOn,
                                long thresholdAmount,
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
        taxYear = taxYear <= 0 ? 2026 : taxYear;
        ruleVersion = ruleVersion == null ? "" : ruleVersion.trim();
        ruleVerifiedOn = ruleVerifiedOn == null ? "" : ruleVerifiedOn.trim();
        thresholdAmount = thresholdAmount <= 0 ? 20_000_000L : thresholdAmount;
        // 기본값은 현행(10%)이다. 예전 코드의 0.11 기본값은 설정이 비면 옛 가산율로 조용히 계산하는 함정이었다.
        grossUpRate = grossUpRate <= 0 ? 0.10 : grossUpRate;
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

    /** 응답에 싣는 한 줄. "어느 법령 · 어느 시행본 · 언제 대조" 가 없으면 세액은 출처 없는 숫자다. */
    public String ruleLabel() {
        StringBuilder sb = new StringBuilder(taxYear + " 귀속");
        if (!ruleVersion.isBlank()) {
            sb.append(" · ").append(ruleVersion);
        }
        if (!ruleVerifiedOn.isBlank()) {
            sb.append(" · 대조일 ").append(ruleVerifiedOn);
        }
        return sb.toString();
    }

    /**
     * 규칙 세트가 세액 계산에 쓸 수 있는 상태인가. 비어 있으면 정상, 하나라도 있으면 판정을 보류한다.
     *
     * <p>검사 항목은 전부 "틀리면 세액이 조용히 틀리는" 것들이다 — 구간이 비거나 순서가 꼬이거나
     * 세율이 1 을 넘는 설정은 예외를 내지 않고 이상한 숫자를 만든다.
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (ruleVersion.isBlank()) {
            out.add("taxdesk.rule-version 이 비어 있다 — 어느 법령 시행본을 적용했는지 알 수 없다");
        }
        if (ruleVerifiedOn.isBlank()) {
            out.add("taxdesk.rule-verified-on 이 비어 있다 — 원문 대조 여부를 알 수 없다");
        }
        if (brackets.isEmpty()) {
            out.add("taxdesk.brackets 가 비어 있다");
            return out;
        }
        long prev = 0;
        for (int i = 0; i < brackets.size(); i++) {
            Bracket b = brackets.get(i);
            boolean last = i == brackets.size() - 1;
            if (b.rate() <= 0 || b.rate() >= 1) {
                out.add("brackets[" + i + "].rate 가 0~1 범위 밖이다: " + b.rate());
            }
            if (b.deduction() < 0) {
                out.add("brackets[" + i + "].deduction 이 음수다");
            }
            if (!last && b.isTop()) {
                out.add("brackets[" + i + "] 가 마지막이 아닌데 상한이 없다");
            }
            if (last && !b.isTop()) {
                out.add("마지막 구간에 상한이 있다 — 그 이상의 과세표준을 계산할 수 없다");
            }
            if (!b.isTop() && b.upTo() <= prev) {
                out.add("brackets[" + i + "].up-to 가 앞 구간보다 작거나 같다");
            }
            prev = b.isTop() ? prev : b.upTo();
        }
        if (grossUpRate >= 0.5 || separateTaxRate >= 0.5) {
            out.add("배당가산율·원천징수세율이 비정상적으로 크다");
        }
        return out;
    }

    public boolean rulesUsable() {
        return problems().isEmpty();
    }
}
