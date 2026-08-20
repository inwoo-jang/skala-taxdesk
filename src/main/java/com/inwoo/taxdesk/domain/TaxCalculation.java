package com.inwoo.taxdesk.domain;

import java.util.List;

/**
 * 세액 계산 결과. <b>숫자만이 아니라 {@code steps} 를 함께 돌려주는 것이 요점이다.</b>
 *
 * <p>도구가 총액만 반환하면 모델이 그 숫자를 설명하다가 다시 산수를 한다.
 * 전개 과정을 같이 주고 <b>인용만</b> 하게 하면 hallucination 이 발생할 지점 자체가 없어진다.
 *
 * @param steps          계산 전개. 답변과 화면이 이것을 그대로 보여준다
 * @param taxableBase    과세표준
 * @param comprehensive  ① 종합과세 산출세액
 * @param separate       ② 분리과세 산출세액
 * @param decidedTax     결정세액 = {@code max(①, ②)} (소득세법 §15 2호) · 10원 미만 절사
 * @param localTax       지방소득세 = 결정세액의 10%. 별도 부과라 분리해 보여준다
 * @param prepaidTax     기납부세액 (자사 원천징수 + 고객이 말한 타사·근로 기납부분)
 * @param payable        납부할세액 = {@code decidedTax - prepaidTax}. <b>음수면 환급</b>
 * @param grossUp        배당가산 4단계
 */
public record TaxCalculation(List<String> steps,
                             long taxableBase,
                             long comprehensive,
                             long separate,
                             long decidedTax,
                             long localTax,
                             long prepaidTax,
                             long payable,
                             GrossUp grossUp) {

    /** ①(종합)이 선택됐는가. 아니면 ②(분리)다. */
    public boolean choseComprehensive() {
        return comprehensive >= separate;
    }

    /** 환급 대상인가. 화면과 답변 문구가 갈린다. */
    public boolean isRefund() {
        return payable < 0;
    }
}
