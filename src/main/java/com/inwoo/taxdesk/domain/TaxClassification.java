package com.inwoo.taxdesk.domain;

import java.util.List;

/**
 * 분리과세 / 종합과세 판정 결과.
 *
 * @param taxation        판정 결과
 * @param financialIncome 과세대상 금융소득 (비과세·분리과세 상품 제외 후)
 * @param threshold       적용된 종합과세기준금액. 설정값이지 모델이 지어낸 값이 아니다
 * @param excess          기준 초과액. 분리과세면 0
 * @param steps           판정 근거 전개. 모델은 이것을 인용만 한다
 */
public record TaxClassification(Taxation taxation,
                                long financialIncome,
                                long threshold,
                                long excess,
                                List<String> steps) {

    public boolean comprehensive() {
        return taxation == Taxation.COMPREHENSIVE;
    }
}
