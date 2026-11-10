package com.inwoo.taxdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.inwoo.taxdesk.domain.TaxSaverProduct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 대화 상태 — 미상과 0 의 구분, 되묻기 유지, 신청 미리보기. */
class ConversationStateTest {

    private final ConversationState state = new ConversationState();

    @Test
    @DisplayName("타사 금액은 말한 적 없으면 null, '없어요' 면 0, 금액을 말하면 그 값")
    void externalAmountDistinguishesUnknownAndZero() {
        assertThat(state.merge("c", "저 종합과세 대상인가요?").externalAmount()).isNull();
        assertThat(state.merge("c", "타사 소득은 없어요").externalAmount()).isZero();
        assertThat(state.merge("c", "다른 은행 이자 500만원 있어요").externalAmount()).isEqualTo(5_000_000L);
        assertThat(state.merge("c", "그럼 세금은요?").externalAmount()).isEqualTo(5_000_000L);   // 유지
    }

    @Test
    @DisplayName("되묻기는 답이 올 때까지 유지된다 — 다른 질문을 사이에 끼워도")
    void awaitingPersistsUntilAnswered() {
        state.merge("c", "저 세금 얼마예요?");
        state.awaitOtherIncome("c");
        assertThat(state.merge("c", "양도세는요?").awaitingOtherIncome()).isTrue();
        assertThat(state.merge("c", "ISA 한도가 얼마죠?").awaitingOtherIncome()).isTrue();

        var answered = state.merge("c", "5천만원이요");
        assertThat(answered.otherIncome()).isEqualTo(50_000_000L);
        assertThat(answered.awaitingOtherIncome()).isFalse();
    }

    @Test
    @DisplayName("소득 종류를 기억한다 — 사업소득금액 1억5천이면 '사업'")
    void remembersIncomeKind() {
        var f = state.merge("c", "사업소득금액 1억 5200만원이에요");
        assertThat(f.otherIncome()).isEqualTo(152_000_000L);
        assertThat(f.otherIncomeKind()).isEqualTo("사업");
        assertThat(state.merge("c", "근로소득금액은 3천만원").otherIncomeKind()).isEqualTo("근로");
    }

    @Test
    @DisplayName("입력란 값은 문장 파싱보다 우선하고 되묻기를 끝낸다")
    void overrideWins() {
        state.merge("c", "근로소득금액 3천만원");
        state.awaitOtherIncome("c");
        var f = state.override("c", 80_500_000L, null, null);
        assertThat(f.otherIncome()).isEqualTo(80_500_000L);
        assertThat(f.awaitingOtherIncome()).isFalse();
    }

    @Test
    @DisplayName("신청 미리보기는 걸고 지울 수 있고 다른 값에 영향을 주지 않는다")
    void pendingActionLifecycle() {
        state.merge("c", "다른 은행 이자 500만원 있어요");
        var f = state.proposeAction("c", TaxSaverProduct.TAX_FREE_SAVINGS, "문의");
        assertThat(f.hasPendingAction()).isTrue();
        assertThat(f.externalAmount()).isEqualTo(5_000_000L);
        assertThat(state.clearAction("c").hasPendingAction()).isFalse();
        assertThat(state.get("c").externalAmount()).isEqualTo(5_000_000L);
    }
}
