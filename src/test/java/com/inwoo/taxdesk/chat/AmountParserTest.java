package com.inwoo.taxdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 고객 문장에서 금액을 꺼내는 규칙.
 *
 * <p>금액 그룹을 {@code [0-9억천만,\s]+} 로만 두면 <b>공백 하나에도 매칭</b>돼 값이 0 으로 읽히고,
 * 고객이 말한 금액이 조용히 사라진다. 실제로 "타사 배당 1,000만원 더하면?" 의 1,000만원이
 * 저장되지 않아 다음 턴에서 합산이 안 됐다. 그래서 숫자로 시작하도록 못 박았다.
 */
class AmountParserTest {

    @Test
    @DisplayName("타사 금액을 항목명 사이에 낱말이 끼어도 읽는다")
    void readsExternalAmount() {
        assertThat(AmountParser.externalAmount("거기에 타사 배당 1,000만원 더하면요?"))
                .isEqualTo(10_000_000L);
        assertThat(AmountParser.externalAmount("다른 증권사 배당 1,000만원"))
                .isEqualTo(10_000_000L);
        assertThat(AmountParser.externalAmount("다른 은행 이자 500만원도 있어요"))
                .isEqualTo(5_000_000L);
    }

    @Test
    @DisplayName("금액이 없으면 0 이 아니라 null 이다 — 미상과 0 을 구분해야 한다")
    void returnsNullWhenNoAmount() {
        assertThat(AmountParser.externalAmount("타사 계좌도 조회해줘")).isNull();
        assertThat(AmountParser.deduction("소득공제가 뭔가요?")).isNull();
    }

    @Test
    @DisplayName("그 밖의 종합소득을 단위 표기 그대로 읽는다")
    void readsOtherIncome() {
        assertThat(AmountParser.otherIncome("근로소득금액 5,000만원 있으면 세금 얼마예요?"))
                .isEqualTo(50_000_000L);
        assertThat(AmountParser.otherIncome("사업소득금액 1억 5200만원이에요"))
                .isEqualTo(152_000_000L);
    }

    @Test
    @DisplayName("없다고 답하면 0 으로 확정한다 — 미상으로 두면 같은 질문을 반복하게 된다")
    void readsExplicitZero() {
        assertThat(AmountParser.otherIncome("다른 소득 없어요")).isZero();
        assertThat(AmountParser.otherIncome("저 세금 얼마 나와요? 다른 소득은 없어요")).isZero();
    }

    @Test
    @DisplayName("총급여는 근로소득금액이 아니므로 되물어야 한다")
    void detectsGrossPay() {
        assertThat(AmountParser.mentionsGrossPay("제 총급여가 8,000만원인데 그걸로 계산해주세요")).isTrue();
        assertThat(AmountParser.mentionsGrossPay("근로소득금액 8,000만원이에요")).isFalse();
    }

    @Test
    @DisplayName("항목명 없는 금액은 되물은 직후에만 쓴다")
    void readsBareAmount() {
        assertThat(AmountParser.bareAmount("1억 5200만원이요")).isEqualTo(152_000_000L);
        assertThat(AmountParser.bareAmount("금융소득이 2천만원 넘으면 어떻게 되나요?")).isNull();
    }

    @Test
    @DisplayName("타사 소득 '없어요' 는 0, 말한 적 없으면 null")
    void externalZeroVsUnknown() {
        assertThat(AmountParser.externalAmount("타사 소득은 없어요")).isZero();
        assertThat(AmountParser.externalAmount("다른 은행 이자는 없습니다")).isZero();
        assertThat(AmountParser.externalAmount("저 종합과세 대상인가요?")).isNull();
        assertThat(AmountParser.externalAmount("타사 계좌도 조회해줘")).isNull();
    }

    @Test
    @DisplayName("매출·수입금액은 사업소득금액이 아니므로 되물어야 한다")
    void detectsSalesRevenue() {
        assertThat(AmountParser.mentionsSalesRevenue("작년 매출이 3억인데요")).isTrue();
        assertThat(AmountParser.mentionsSalesRevenue("사업소득금액 1억이에요")).isFalse();
        assertThat(AmountParser.mentionsGrossPay("연봉 8천인데 세금 얼마예요?")).isTrue();
    }

    @Test
    @DisplayName("소득 종류를 읽는다")
    void readsIncomeKind() {
        assertThat(AmountParser.otherIncomeKind("근로소득금액 5,000만원")).isEqualTo("근로");
        assertThat(AmountParser.otherIncomeKind("사업소득 1억")).isEqualTo("사업");
        assertThat(AmountParser.otherIncomeKind("다른 소득 3천만원")).isNull();
    }
}
