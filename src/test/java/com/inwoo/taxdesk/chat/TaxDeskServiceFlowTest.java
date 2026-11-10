package com.inwoo.taxdesk.chat;

import static com.inwoo.taxdesk.support.ServiceHarness.CONV;
import static com.inwoo.taxdesk.support.ServiceHarness.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.support.ServiceHarness;
import com.inwoo.taxdesk.support.TestProps;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 서비스 흐름 — 모델을 언제 부르지 않는가, 도구를 어떤 순서로 부르는가, 어떤 단서를 붙이는가.
 * <b>모델·API 키·네트워크 없이 돈다</b> ({@link ServiceHarness}).
 */
class TaxDeskServiceFlowTest {

    final ServiceHarness h = new ServiceHarness();

    @Nested
    @DisplayName("모델을 부르지 않는 경우")
    class NoModel {

        @Test
        @DisplayName("범위 밖 거절 — 토큰 0 · 도구 0 · 모델 0")
        void refusalCostsNothing() {
            var a = h.service.ask(USER, CONV, "양도세 얼마예요?");

            assertThat(a.route()).isEqualTo(Route.OUT_OF_SCOPE);
            assertThat(a.tokens().prompt()).isZero();
            assertThat(a.toolCalls()).isEmpty();
            assertThat(h.main.calls()).isZero();
            verify(h.incomeRepo, never()).findByOwnerIdAndYear(anyString(), anyInt());
        }

        @Test
        @DisplayName("총급여를 말하면 계산하지 않고 되묻는다")
        void grossPayIsReasked() {
            h.seedAntman();
            h.routerModel.reply("CALCULATION");
            var a = h.service.ask(USER, CONV, "제 총급여가 8,000만원인데 그걸로 계산해주세요");

            assertThat(a.answer()).contains("근로소득금액");
            assertThat(a.calculation()).isNull();
            assertThat(h.main.calls()).isZero();
        }

        @Test
        @DisplayName("매출을 말하면 사업소득금액을 되묻는다")
        void salesRevenueIsReasked() {
            h.seedAntman();
            h.routerModel.reply("CALCULATION");
            var a = h.service.ask(USER, CONV, "작년 매출이 3억인데 세금 얼마예요?");

            assertThat(a.answer()).contains("사업소득금액");
            assertThat(a.calculation()).isNull();
            assertThat(h.main.calls()).isZero();
        }

        @Test
        @DisplayName("규칙 버전이 없으면 판정을 보류한다")
        void unversionedRulesHold() {
            var u = new ServiceHarness(TestProps.unversioned());
            u.seedAntman();
            var a = u.service.ask(USER, CONV, "저 종합과세 대상인가요?");

            assertThat(a.answer()).isEqualTo(TaxDeskService.RULES_UNAVAILABLE);
            assertThat(a.notices()).anyMatch(n -> n.contains("rule-version"));
            assertThat(a.toolCalls()).isEmpty();
            assertThat(u.main.calls()).isZero();
        }

        @Test
        @DisplayName("상한 없는 세율표도 보류한다")
        void brokenBracketsHold() {
            var broken = new TaxDeskProperties(2026, "v", "2026-08-20", 20_000_000L, 0.10, 0.14, 0.10,
                    List.of(new TaxDeskProperties.Bracket(14_000_000L, 0.06, 0L)),
                    Map.of(), Map.of(), 2000, 20, 4, 0.25, 0.30, 100, "s", "s", "x");
            assertThat(broken.problems()).anyMatch(p -> p.contains("마지막 구간"));
            var b = new ServiceHarness(broken);
            b.seedIsabu();
            assertThat(b.service.ask(USER, CONV, "저 세금 얼마예요?", 80_500_000L, null, null).answer())
                    .isEqualTo(TaxDeskService.RULES_UNAVAILABLE);
        }

        @Test
        @DisplayName("계좌 조회가 실패하면 0원이 아니라 보류다")
        void lookupFailureHolds() {
            when(h.incomeRepo.findByOwnerIdAndYear(USER, 2026))
                    .thenThrow(new DataAccessResourceFailureException("db down"));
            var a = h.service.ask(USER, CONV, "저 종합과세 대상인가요?");

            assertThat(a.answer()).isEqualTo(TaxDeskService.LOOKUP_FAILED);
            assertThat(a.calculation()).isNull();
            assertThat(a.toolCalls()).noneMatch(t -> t.name().equals("classifyTaxation"));
            assertThat(h.main.calls()).isZero();
        }

        @Test
        @DisplayName("규정 질문인데 근거가 없으면 답변을 보류한다")
        void regulationWithoutEvidenceHolds() {
            h.routerModel.reply("REGULATION");
            var a = h.service.ask(USER, CONV, "배당소득 세율이 몇 퍼센트인가요?");

            assertThat(a.answer()).isEqualTo(TaxDeskService.NO_EVIDENCE);
            assertThat(a.citations()).isEmpty();
            assertThat(h.main.calls()).isZero();
            assertThat(a.notices()).anyMatch(n -> n.contains("근거를 찾지 못했습니다"));
        }

        @Test
        @DisplayName("검색 장애도 보류다 — 그리고 장애라고 적는다")
        void searchFailureIsReported() {
            h.routerModel.reply("REGULATION");
            h.store.failNext = true;
            var a = h.service.ask(USER, CONV, "배당소득 세율이 몇 퍼센트인가요?");

            assertThat(a.answer()).isEqualTo(TaxDeskService.NO_EVIDENCE);
            assertThat(a.notices()).anyMatch(n -> n.contains("장애"));
            assertThat(h.main.calls()).isZero();
        }

        @Test
        @DisplayName("종합과세인데 다른 소득이 미상이면 계산하지 않고 되묻는다 — 모델도 부르지 않는다")
        void comprehensiveWithoutOtherIncomeReasks() {
            h.seedIsabu();
            var a = h.service.ask(USER, CONV, "저 세금 얼마 나와요?");

            assertThat(a.calculation()).isNull();
            assertThat(a.answer()).contains("아직 알려주시지 않았습니다");
            assertThat(a.toolCalls()).extracting(AnswerDto.ToolCall::name)
                    .containsExactly("lookupMyIncome", "classifyTaxation");
            assertThat(h.main.calls()).isZero();
            assertThat(h.state.get(CONV).awaitingOtherIncome()).isTrue();
        }
    }

    @Nested
    @DisplayName("판정과 계산")
    class Judgement {

        @Test
        @DisplayName("분리과세 고객 — calculateTax 를 부르지 않고, 타사 미확인 단서를 붙인다")
        void separateDoesNotCalculate() {
            h.seedAntman();
            var a = h.service.ask(USER, CONV, "저 종합과세 대상인가요?");

            assertThat(a.route()).isEqualTo(Route.HYBRID);
            assertThat(a.calculation()).isNull();
            assertThat(a.toolCalls()).extracting(AnswerDto.ToolCall::name)
                    .containsExactly("lookupMyIncome", "classifyTaxation");
            assertThat(a.toolCalls().get(1).result()).isEqualTo("SEPARATE");
            assertThat(a.toolCalls().get(1).args()).contains("external=미확인");
            assertThat(a.notices()).anyMatch(n -> n.contains("타사"));
            assertThat(h.main.lastSystemText()).contains("[단서] 타사(미연동 기관) 금융소득은 미확인");
            assertThat(a.overview()).isNotNull();
            assertThat(a.overview().externalStatus()).isEqualTo("UNKNOWN");
            assertThat(a.ruleVersion()).contains("2026 귀속").contains("제21221호");
            assertThat(h.main.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("타사 없다고 답하면 단서가 '확인'으로 바뀐다 — 0 과 미상은 다르다")
        void explicitZeroExternal() {
            h.seedAntman();
            var a = h.service.ask(USER, CONV, "타사 금융소득은 없어요. 저 종합과세 대상인가요?");

            assertThat(h.state.get(CONV).externalAmount()).isZero();
            assertThat(a.toolCalls().get(1).args()).contains("external=0");
            assertThat(a.notices()).noneMatch(n -> n.contains("타사 소득이 있으면 결과가 달라질"));
            assertThat(h.main.lastSystemText()).contains("[확인] 고객이 타사 금융소득은 없다고 답했다");
        }

        @Test
        @DisplayName("타사 배당을 알려주면 거절하지 않고 합산해 판정이 뒤집힌다 (MT-02)")
        void declaredExternalFlipsJudgement() {
            h.seedAntman();
            var a = h.service.ask(USER, CONV, "거기에 타사 배당 1,000만원 더하면요?");

            assertThat(a.route()).isNotEqualTo(Route.OUT_OF_SCOPE);
            assertThat(a.toolCalls().get(1).args()).contains("external=10000000");
            assertThat(a.toolCalls().get(1).result()).isEqualTo("COMPREHENSIVE");
            assertThat(a.calculation()).isNull();   // 다른 소득 미상 → 되묻기
            assertThat(h.state.get(CONV).awaitingOtherIncome()).isTrue();
        }

        @Test
        @DisplayName("이미 연동된 기관을 수기로 다시 말하면 중복 경고를 붙인다")
        void duplicateDeclarationWarns() {
            h.seedAntman();
            var a = h.service.ask(USER, CONV, "국민은행 거 다른 은행 이자 800만원도 있어요");

            assertThat(a.notices()).anyMatch(n -> n.contains("국민은행") && n.contains("중복"));
        }

        @Test
        @DisplayName("종합과세 + 다른 소득 입력 → 계산. 기납부세액에 ISA 분리과세 원천징수는 들어가지 않는다")
        void comprehensiveCalculatesWithTaxableWithholdingOnly() {
            h.seedIsabu();
            var a = h.service.ask(USER, CONV, "저 세금 얼마예요?", 80_500_000L, 1_500_000L, null);

            assertThat(a.calculation()).isNotNull();
            assertThat(a.calculation().decidedTax()).isEqualTo(16_250_660L);
            // 과세대상분 원천징수 1,277,980 + 1,736,000 = 3,013,980. ISA 460,800 은 제외.
            assertThat(a.calculation().prepaidTax()).isEqualTo(3_013_980L);
            assertThat(a.toolCalls()).extracting(AnswerDto.ToolCall::name)
                    .containsExactly("lookupMyIncome", "classifyTaxation", "calculateTax");
            assertThat(a.disclaimer()).contains("실습용");
            assertThat(a.notices()).anyMatch(n -> n.contains("고배당기업"));
            assertThat(a.notices()).anyMatch(n -> n.contains("비과세·분리과세로 분류된 9,120,000원"));
        }

        @Test
        @DisplayName("되묻기 중 다른 질문을 해도 되묻기는 유지되고 답에 상기 문구가 붙는다")
        void reaskSurvivesOtherQuestions() {
            h.seedIsabu();
            h.service.ask(USER, CONV, "저 세금 얼마예요?");
            assertThat(h.state.get(CONV).awaitingOtherIncome()).isTrue();

            var other = h.service.ask(USER, CONV, "양도세는 얼마예요?");
            assertThat(other.route()).isEqualTo(Route.OUT_OF_SCOPE);
            assertThat(other.answer()).contains("앞서 여쭤본");
            assertThat(h.state.get(CONV).awaitingOtherIncome()).isTrue();

            var answered = h.service.ask(USER, CONV, "8,050만원이요");
            assertThat(h.state.get(CONV).otherIncome()).isEqualTo(80_500_000L);
            assertThat(h.state.get(CONV).awaitingOtherIncome()).isFalse();
            assertThat(answered.calculation()).isNotNull();
        }

        @Test
        @DisplayName("도구 호출 횟수는 코드가 정한 상한(4)을 넘지 않는다")
        void toolCallsAreBounded() {
            h.seedIsabu();
            var a = h.service.ask(USER, CONV, "저 세금 얼마예요? 신고 기한은 며칠 남았나요?", 80_500_000L, 1_500_000L, null);
            assertThat(a.toolCalls().size()).isLessThanOrEqualTo(4);
        }
    }

    @Nested
    @DisplayName("근거 — 검색된 것과 인용된 것은 다르다")
    class Evidence {

        @Test
        @DisplayName("검색은 귀속연도 필터를 건다")
        void searchFiltersByYear() {
            h.routerModel.reply("REGULATION");
            h.seedLawDoc("소득세법 §14③", "이자소득과 배당소득의 합계액이 2천만원 이하이면 분리과세");
            h.service.ask(USER, CONV, "2천만원 이하이면 분리과세인가요?");

            assertThat(h.store.requests).hasSize(1);
            assertThat(String.valueOf(h.store.requests.get(0).getFilterExpression())).contains("effectiveYear");
        }

        @Test
        @DisplayName("답변이 실제로 인용한 조문만 used 가 참이다")
        void usedCitationsAreMarked() {
            h.routerModel.reply("REGULATION");
            h.seedLawDoc("소득세법 §14③", "이자소득과 배당소득의 합계액이 2천만원 이하이면 분리과세");
            h.seedLawDoc("소득세법 §62", "2천만원 비교과세 세액 계산의 특례");
            h.main.reply("2천만원 이하이면 분리과세입니다. [출처: 소득세법 §14③ (2026 귀속)]");

            var a = h.service.ask(USER, CONV, "2천만원 이하이면 분리과세인가요?");

            assertThat(a.citations()).hasSize(2);
            assertThat(a.citations()).filteredOn(AnswerDto.Citation::used).extracting(AnswerDto.Citation::article)
                    .containsExactly("소득세법 §14③");
            assertThat(a.citations().get(0).kind()).isEqualTo("LAW");
            assertThat(a.citations().get(0).lawVersion()).contains("제21221호");
            assertThat(a.citations().get(0).excerpt()).isNotBlank();
            assertThat(h.main.lastSystemText()).contains("제21221호");
        }

        @Test
        @DisplayName("회수되지 않은 조문을 인용하면 답변을 바꾸고 고지한다")
        void ungroundedCitationIsReplaced() {
            h.routerModel.reply("REGULATION");
            h.seedLawDoc("소득세법 §14③", "이자소득과 배당소득의 합계액이 2천만원 이하이면 분리과세");
            h.main.reply("비영업대금은 14%입니다. [출처: 소득세법 §129①1호 (2026 귀속)]");

            var a = h.service.ask(USER, CONV, "2천만원 이하이면 분리과세인가요?");

            assertThat(a.answer()).startsWith("확인되지 않습니다");
            assertThat(a.notices()).anyMatch(n -> n.contains("회수되지 않은 조문"));
        }

        @Test
        @DisplayName("판정 경로에서 근거가 없으면 보류하지 않고 진행하되 고지한다")
        void hybridWithoutDocsProceeds() {
            h.seedAntman();
            h.store.failNext = true;
            var a = h.service.ask(USER, CONV, "저 종합과세 대상인가요?");

            assertThat(a.route()).isEqualTo(Route.HYBRID);
            assertThat(a.toolCalls()).isNotEmpty();
            assertThat(a.notices()).anyMatch(n -> n.contains("근거 검색에 장애"));
            assertThat(h.main.calls()).isEqualTo(1);
        }
    }
}
