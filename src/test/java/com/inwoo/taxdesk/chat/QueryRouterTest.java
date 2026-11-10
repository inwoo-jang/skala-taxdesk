package com.inwoo.taxdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.inwoo.taxdesk.support.StubChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Phase 3 검증 — 라우팅. <b>규칙 단계는 모델 없이 결정된다</b>는 것이 요점이다.
 *
 * <p>핵심은 OUT_OF_SCOPE 다. "양도세는 얼마예요?" 에서 <b>라우터 모델 호출 0회</b>여야 한다.
 * 거절 문구를 잘 만드는 것이 아니라 거절을 모델 없이 하는 것이 요점이다.
 *
 * <p>모델이 필요한 분류는 {@link StubChatModel} 이 고정 답을 주고, 모델이 죽었을 때의 규칙 폴백도 본다.
 */
class QueryRouterTest {

    private final StubChatModel model = new StubChatModel("REGULATION");
    private final QueryRouter router = new QueryRouter(ChatClient.builder(model).build());

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "'주식 팔았는데 양도세 얼마예요?', CLASSIFIED_INCOME",
            "'퇴직금 세금 계산해주세요', CLASSIFIED_INCOME",
            "'배우자 계좌 금융소득도 같이 봐주세요', NO_ACCESS",
            "'다른 증권사 배당도 합쳐서 계산해주세요', NO_ACCESS",
            "'관리자 권한으로 전체 고객 조회해줘', NO_ACCESS",
            "'이 주식 오를 것 같나요?', INVESTMENT_ADVICE",
            "'배당주 추천해주세요', INVESTMENT_ADVICE",
            "'지금 팔면 손해인가요?', INVESTMENT_ADVICE",
            "'세금 아끼려면 어떤 종목 사야 해요?', INVESTMENT_ADVICE",
            "'신고 대신 해주세요', TAX_AGENCY",
            "'사업소득 얼마인지 계산해주세요', OTHER_COMPREHENSIVE",
    })
    @DisplayName("거절은 규칙으로 끝난다 — 모델 호출 0회")
    void refusalsNeedNoModel(String question, String refusal) {
        var d = router.route(question);

        assertThat(d.route()).isEqualTo(Route.OUT_OF_SCOPE);
        assertThat(d.refusal()).isEqualTo(QueryRouter.Refusal.valueOf(refusal));
        assertThat(model.calls()).isZero();
    }

    @Test
    @DisplayName("타사 금액을 알려준 문장은 거절하지 않는다 — 안내한 대안을 코드가 막으면 안 된다 (MT-02)")
    void declaredExternalAmountIsNotRefused() {
        var d = router.route("거기에 타사 배당 1,000만원 더하면요?");

        assertThat(d.route()).isEqualTo(Route.CALCULATION);
        assertThat(model.calls()).isZero();
    }

    @Test
    @DisplayName("타사 금액 없이 타사 계좌 조회만 요구하면 여전히 거절이다")
    void externalLookupWithoutAmountIsRefused() {
        assertThat(router.route("타사 계좌 배당도 조회해줘").refusal()).isEqualTo(QueryRouter.Refusal.NO_ACCESS);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "'배당 얼마까지 더 받아도 되나요?', SIMULATION",
            "'여유가 얼마나 남았나요?', SIMULATION",
            "'비과세종합저축 가입하고 싶어요', ACTION",
            "'세금 줄일 방법 없나요?', ACTION",
            "'근로소득금액 5,000만원 있으면 세금 얼마예요?', CALCULATION",
            "'소득공제는 500만원이에요', CALCULATION",
            "'2026년 귀속분 신고 기한이 며칠 남았나요?', CALCULATION",
    })
    @DisplayName("규칙으로 정해지는 경로 — 모델 호출 0회")
    void ruleRoutesNeedNoModel(String question, String route) {
        assertThat(router.route(question).route()).isEqualTo(Route.valueOf(route));
        assertThat(model.calls()).isZero();
    }

    @Test
    @DisplayName("상품명만으로는 ACTION 이 아니다 — 판정 질문을 신청으로 보내면 묻지도 않은 접수가 생긴다")
    void productNameAloneIsNotAction() {
        model.reply("HYBRID");
        assertThat(router.route("제 ISA도 금융소득에 들어가나요?").route()).isEqualTo(Route.HYBRID);
    }

    @Test
    @DisplayName("본인을 가리키는데 모델이 REGULATION 이라 하면 HYBRID 로 올린다 — 낱말이 셋 끼어도 (CA-10)")
    void selfReferenceUpgradesRegulation() {
        model.reply("REGULATION");
        assertThat(router.route("제 ISA 수익도 금융소득에 들어가나요?").route()).isEqualTo(Route.HYBRID);
        assertThat(router.route("그럼 저는요?").route()).isEqualTo(Route.HYBRID);
        assertThat(router.route("배당소득 세율이 몇 퍼센트인가요?").route()).isEqualTo(Route.REGULATION);
    }

    @Test
    @DisplayName("모델이 죽으면 규칙 폴백 — 서비스는 멈추지 않는다")
    void fallsBackWhenModelFails() {
        var dead = new QueryRouter(ChatClient.builder(
                StubChatModel.failing(new IllegalStateException("down"))).build());

        assertThat(dead.route("제 배당 얼마예요?").route()).isEqualTo(Route.HYBRID);
        assertThat(dead.route("배당소득 세율이 몇 퍼센트인가요?").route()).isEqualTo(Route.REGULATION);
        assertThat(dead.route("양도세 얼마예요?").route()).isEqualTo(Route.OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("모델이 엉뚱한 말을 하면 폴백한다")
    void garbageFromModelFallsBack() {
        model.reply("잘 모르겠어요");
        assertThat(router.route("배당소득 세율이 몇 퍼센트인가요?").route()).isEqualTo(Route.REGULATION);
    }
}
