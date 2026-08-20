package com.inwoo.taxdesk.domain;

/**
 * 배당가산(Gross-up) 계산의 <b>네 단계</b>. 이 실습에서 가장 틀리기 쉬운 지점이다.
 *
 * <p>흔한 오류는 {@code 배당소득 × 10%} 라는 필드 하나를 두는 것이다. 두 가지가 동시에 틀린다.
 * <ol>
 *   <li><b>가산율은 10%다.</b> 소득세법 §17③ "100분의 10". 2024년 1월 1일 이후 발생 배당분부터
 *       11% → 10% 로 인하됐는데, 인터넷 자료 대부분이 아직 11% 로 되어 있다</li>
 *   <li><b>배당 전액이 대상이 아니다.</b> Gross-up 대상 배당 중
 *       <b>종합과세기준금액(2,000만원)을 초과하는 부분</b>만이다 (§56④)</li>
 * </ol>
 *
 * <p>그래서 한 단계씩 쪼갠다. 계산 전개({@code steps[]})가 이 네 줄을 그대로 보여준다.
 *
 * <pre>
 *   이자소득                    1,000만원
 *   Gross-up 대상 국내배당      1,500만원
 *   ─────────────────────────────────────
 *   금융소득                    2,500만원
 *   종합과세기준                2,000만원
 *   기준 초과액                   500만원
 *
 *   ① eligibleDividend  = 1,500만원   Gross-up 이 가능한 배당 (내국법인 · 법인세 과세된 재원)
 *   ② targetAmount      =   500만원   그중 기준금액을 초과하는 부분  ← 1,500만원이 아니다
 *   ③ amount            =    50만원   targetAmount × 10%
 *   ④ dividendTaxCredit =    50만원   산출세액에서 빼주는 배당세액공제 (한도 적용 대상)
 * </pre>
 *
 * <p>③이 종합소득금액에 <b>더해지고</b>, ④가 산출세액에서 <b>빠진다.</b> 소득을 늘려 세금을
 * 더 걷으려는 것이 아니라, 법인 단계에서 이미 낸 법인세와의 이중과세를 조정하는 구조다.
 *
 * <p><b>대상이 아닌 배당</b> — 외국법인 배당({@link IncomeType#FOREIGN_DIVIDEND}),
 * 법인세가 과세되지 않은 재원의 배당, 일부 자본준비금 감액배당, 비과세·분리과세 배당.
 * 특히 해외주식 배당에 10% 를 붙이면 <b>틀린다</b> — 국내 법인세 이중과세 조정 구조와 무관하다.
 *
 * @param eligibleDividend  ① Gross-up 이 가능한 배당 합계
 * @param targetAmount      ② 그중 기준금액 초과분 = {@code min(eligibleDividend, 금융소득 - 기준금액)}
 * @param amount            ③ 배당가산액 = {@code targetAmount × grossUpRate}
 * @param dividendTaxCredit ④ 배당세액공제액 (§56 · 법정 한도 적용 후)
 */
public record GrossUp(long eligibleDividend,
                      long targetAmount,
                      long amount,
                      long dividendTaxCredit) {

    /** 아무것도 가산되지 않은 상태 (분리과세 종결 · 대상 배당 없음). */
    public static GrossUp none() {
        return new GrossUp(0L, 0L, 0L, 0L);
    }

    public boolean applies() {
        return targetAmount > 0;
    }
}
