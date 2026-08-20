package com.inwoo.taxdesk.domain;

/**
 * 세액공제 한 건. <b>공제를 금액 하나로 뭉개지 않고 출처(어느 소득에서 나온 공제인가)를 들고 다닌다.</b>
 *
 * <p>이유는 셋이다.
 * <ol>
 *   <li>공제마다 <b>한도 계산식이 다르다.</b> 배당세액공제와 기장세액공제는 한도가 전혀 다르게 잡힌다</li>
 *   <li>금융소득과 사업소득이 섞이면 <b>어느 공제가 왜 적용됐는지</b>를 답변에서 설명해야 한다</li>
 *   <li>계산 전개({@code steps[]})에 "배당세액공제 50만원 (§56)" 처럼 <b>근거 조문</b>을 붙여야 한다</li>
 * </ol>
 *
 * <p>지금 실습 범위(금융소득)에서 실제로 계산하는 것은 {@link Dividend} 뿐이지만,
 * 구조를 미리 열어 두지 않으면 나중에 공제가 늘 때 {@code long taxCredit} 하나에 전부 합산되어
 * 무엇이 얼마인지 설명할 수 없게 된다.
 */
public sealed interface TaxCredit {

    /** 공제액 (원). */
    long amount();

    /** 근거 조문. 계산 전개에 그대로 인용된다. */
    String article();

    /**
     * 배당세액공제 — 소득세법 §56. 금융소득 관련.
     *
     * <p>{@link GrossUp#amount()} 상당액을 산출세액에서 공제한다. 다만 법정 한도가 있어
     * 기계적으로 가산액 전액을 빼면 안 된다.
     */
    record Dividend(long amount) implements TaxCredit {
        @Override
        public String article() {
            return "소득세법 §56";
        }
    }

    /**
     * 외국납부세액공제 — 소득세법 §57. 해외 배당·이자에서 원천지국에 낸 세액.
     *
     * <p>해외주식 배당은 Gross-up 대상이 <b>아니지만</b> 이 공제 대상은 <b>될 수 있다.</b>
     * 둘을 혼동하면 "해외배당은 아무 혜택이 없다"는 틀린 안내가 나간다.
     */
    record Foreign(long amount) implements TaxCredit {
        @Override
        public String article() {
            return "소득세법 §57";
        }
    }

    /**
     * 기장세액공제 — 소득세법 §56의2. <b>사업소득 전용이라 이 서비스의 범위 밖이다.</b>
     *
     * <p>사업소득금액 / 종합소득금액 비율로 산출세액에 곱해 계산하므로 사업 장부가 필요하다.
     * 여기에 남겨 둔 이유는 "왜 우리가 이걸 계산해 드릴 수 없는지"를 답변에서 설명하기 위해서다.
     */
    record Bookkeeping(long amount) implements TaxCredit {
        @Override
        public String article() {
            return "소득세법 §56의2";
        }
    }

    /** 그 밖의 공제. 실습 범위에서는 계산하지 않고 고객 입력값을 그대로 받는다. */
    record Other(long amount, String article) implements TaxCredit {
    }
}
