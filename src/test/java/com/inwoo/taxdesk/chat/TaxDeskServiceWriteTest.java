package com.inwoo.taxdesk.chat;

import static com.inwoo.taxdesk.support.ServiceHarness.CONV;
import static com.inwoo.taxdesk.support.ServiceHarness.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.inwoo.taxdesk.domain.ApplicationStatus;
import com.inwoo.taxdesk.domain.TaxSaverProduct;
import com.inwoo.taxdesk.support.ServiceHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 신청(쓰기)은 <b>확인 없이 실행되지 않는다.</b> 미리보기 → 명시적 "예" → 접수.
 * 중복·취소·만료·실패·계정 경계까지 전부 모델 없이 검증한다.
 */
class TaxDeskServiceWriteTest {

    final ServiceHarness h = new ServiceHarness();

    @BeforeEach
    void seed() {
        h.seedAntman();
    }

    @Test
    @DisplayName("ACTION 턴은 미리보기만 만들고 저장소에는 아무것도 남기지 않는다")
    void actionOnlyPreviews() {
        var a = h.service.ask(USER, CONV, "세금 줄일 방법 없나요?");

        assertThat(a.route()).isEqualTo(Route.ACTION);
        assertThat(a.pendingAction()).isNotNull();
        assertThat(a.pendingAction().mock()).isTrue();
        assertThat(a.pendingAction().product()).isEqualTo("TAX_FREE_SAVINGS");
        assertThat(h.savedApplications).isEmpty();
        assertThat(a.toolCalls()).noneMatch(t -> t.name().equals("requestEligibilityCheck"));
        assertThat(a.notices()).anyMatch(n -> n.contains("접수되지 않았습니다"));
        assertThat(h.state.get(CONV).hasPendingAction()).isTrue();
    }

    @Test
    @DisplayName("'신청합니다' 라고 답해야 접수된다 — 모델 없이")
    void confirmationExecutes() {
        h.service.ask(USER, CONV, "세금 줄일 방법 없나요?");
        int modelCallsBefore = h.main.calls();

        var a = h.service.ask(USER, CONV, "신청합니다");

        assertThat(h.savedApplications).hasSize(1);
        assertThat(h.savedApplications.get(0).getOwnerId()).isEqualTo(USER);
        assertThat(h.savedApplications.get(0).getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(h.savedApplications.get(0).getProduct()).isEqualTo(TaxSaverProduct.TAX_FREE_SAVINGS);
        assertThat(a.answer()).contains("접수했습니다").contains("실습용");
        assertThat(a.toolCalls()).extracting(AnswerDto.ToolCall::name).containsExactly("requestEligibilityCheck");
        assertThat(h.main.calls()).isEqualTo(modelCallsBefore);
        assertThat(h.state.get(CONV).hasPendingAction()).isFalse();
    }

    @Test
    @DisplayName("'아니요' 면 취소되고 저장소는 비어 있다")
    void declineCancels() {
        h.service.ask(USER, CONV, "세금 줄일 방법 없나요?");
        var a = h.service.ask(USER, CONV, "아니요");

        assertThat(h.savedApplications).isEmpty();
        assertThat(a.answer()).contains("접수하지 않았습니다");
        assertThat(h.state.get(CONV).hasPendingAction()).isFalse();
    }

    @Test
    @DisplayName("다른 질문을 하면 미리보기는 만료된다 — 애매하면 실행하지 않는다")
    void otherQuestionExpiresPreview() {
        h.service.ask(USER, CONV, "세금 줄일 방법 없나요?");
        var a = h.service.ask(USER, CONV, "네 그런데 ISA 비과세 한도가 얼마예요?");

        assertThat(h.savedApplications).isEmpty();
        assertThat(a.notices()).anyMatch(n -> n.contains("만료"));
        assertThat(h.state.get(CONV).hasPendingAction()).isFalse();
    }

    @Test
    @DisplayName("같은 상품이 PENDING 이면 다시 접수하지 않는다")
    void duplicateIsNotCreated() {
        h.service.ask(USER, CONV, "세금 줄일 방법 없나요?");
        h.service.ask(USER, CONV, "신청합니다");
        assertThat(h.savedApplications).hasSize(1);

        var again = h.service.ask(USER, CONV, "비과세종합저축 가입하고 싶어요");
        assertThat(again.pendingAction()).isNull();
        assertThat(again.notices()).anyMatch(n -> n.contains("이미 처리 대기 중"));
        h.service.ask(USER, CONV, "신청합니다");   // 미리보기가 없으니 아무것도 하지 않는다
        assertThat(h.savedApplications).hasSize(1);
    }

    @Test
    @DisplayName("접수 중 저장소 오류가 나면 접수되지 않았다고 말한다")
    void saveFailureIsReported() {
        h.service.ask(USER, CONV, "세금 줄일 방법 없나요?");
        when(h.appRepo.save(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        var a = h.service.ask(USER, CONV, "네");

        assertThat(a.answer()).contains("접수되지 않았습니다");
        assertThat(a.notices()).anyMatch(n -> n.contains("오류"));
        assertThat(h.state.get(CONV).hasPendingAction()).isFalse();
    }

    @Test
    @DisplayName("다른 계정의 미리보기는 내 확인으로 실행되지 않는다 — 대화방 키가 계정으로 묶인다")
    void previewIsScopedToConversation() {
        h.service.ask(USER, CONV, "세금 줄일 방법 없나요?");
        var a = h.service.ask("richman", "richman:t", "신청합니다");

        // richman 의 "신청합니다"는 richman 자신의 새 요청이다 — 미리보기는 만들 수 있어도 접수는 없다.
        assertThat(h.savedApplications).isEmpty();
        assertThat(a.toolCalls()).noneMatch(t -> t.name().equals("requestEligibilityCheck"));
        // antman 의 미리보기는 건드려지지 않은 채 antman 의 대화방에 남아 있다.
        assertThat(h.state.get(CONV).hasPendingAction()).isTrue();
    }
}
