package com.inwoo.taxdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.domain.TaxCalculation;
import com.inwoo.taxdesk.domain.TaxSimulation;
import com.inwoo.taxdesk.domain.Taxation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Phase 4 검증 — 세액 계산. <b>모델을 부르지 않는 순수 단위 테스트다.</b>
 *
 * <p>테스트 케이스가 임의로 고른 값이 아니라는 점이 중요하다. 시드 5명은 각각
 * <b>서로 다른 버그를 잡도록</b> 설계됐고, 그 근거가 docs/QUESTIONS.md 의 뮤테이션 검출 행렬이다.
 *
 * <pre>
 *   심은 버그                          잡는 사람
 *   M1 ISA·비과세를 제외하지 않음        isabu
 *   M2 Gross-up 대상 min → max         isabu · richman · americano
 *   M3 외국배당도 Gross-up 대상          richman · americano
 *   M4 경계를 "초과"가 아닌 "이상"으로     (시드 아님 → 경계값 테스트가 담당)
 *   M5 비교과세 생략(① 만 계산)          jonber
 *   M6 Gross-up 율을 옛 11% 로          isabu · richman
 *   M7 판정 없이 calculateTax 부터 호출   antman
 * </pre>
 *
 * <p>5명의 검출 패턴이 전부 다르므로 <b>누구를 빼도 잡히지 않는 버그가 생긴다.</b>
 * 기대값은 세법 원문(소득세법 제21221호, 시행 2026.7.1)을 근거로 손계산·검산한 값이지
 * 구현이 뱉은 값을 받아 적은 것이 아니다.
 */
@DisplayName("TaxCalcTools — 2026 귀속 금융소득 세액")
class TaxCalcToolsTest {

    private final TaxCalcTools tools = new TaxCalcTools(props(0.10));

    // ------------------------------------------------------------------ 시드 5명

    @Nested
    @DisplayName("시드 5명 — 각자 다른 버그를 잡는다")
    class SeedUsers {

        @Test
        @DisplayName("antman — 분리과세 종결. 세액 계산 자체를 하지 않는다 (M7)")
        void antman() {
            var c = tools.classifyTaxation(8_347_210, 6_912_500, 0, 0);

            assertThat(c.taxation()).isEqualTo(Taxation.SEPARATE);
            assertThat(c.financialIncome()).isEqualTo(15_259_710L);
            assertThat(c.excess()).isZero();
            assertThat(c.steps()).anyMatch(s -> s.contains("신고 대상이 아님"));

            // M7 — 판정을 건너뛰고 계산부터 하려 하면 코드가 막는다.
            // 프롬프트로 "분리과세면 계산하지 마"라고 부탁하는 것과 다르다.
            assertThatThrownBy(() -> tools.calculateTax(15_259_710L, 6_912_500L, 41_500_000L, 1_500_000L, 2_136_350L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("classifyTaxation");
        }

        @Test
        @DisplayName("isabu — ISA 제외를 빠뜨리면 179만원 틀린다 (M1)")
        void isabu() {
            // 계좌 총액 30,648,440 중 ISA 비과세 4,000,000 + ISA 초과분 5,120,000(9% 분리과세)을
            // 제외한 21,528,440 이 과세대상이다.
            var r = tools.calculateTax(21_528_440L, 12_400_000L, 80_500_000L, 1_500_000L, 3_013_960L);

            assertThat(r.decidedTax()).isEqualTo(16_250_660L);
            assertThat(r.choseComprehensive()).isTrue();
            assertThat(r.grossUp().targetAmount()).isEqualTo(1_528_440L);
            assertThat(r.grossUp().amount()).isEqualTo(152_844L);

            // M1 — ISA 를 제외하지 않고 30,648,440 으로 계산했을 때의 값.
            // 두 값이 다르다는 것이 이 테스트가 M1 을 잡는다는 뜻이다.
            var wrong = tools.calculateTax(30_648_440L, 12_400_000L, 80_500_000L, 1_500_000L, 3_013_960L);
            assertThat(wrong.decidedTax()).isEqualTo(18_044_800L);
            assertThat(wrong.decidedTax() - r.decidedTax()).isEqualTo(1_794_140L);
        }

        @Test
        @DisplayName("jonber — 비교과세에서 ② 가 선택된다 (M5)")
        void jonber() {
            var r = tools.calculateTax(25_427_830L, 24_180_000L, 0L, 1_500_000L, 3_559_890L);

            // ① 만 계산하고 끝냈다면 2,525,453 이 답이 됐을 것이다. max 를 취해야 3,559,890 이다.
            assertThat(r.comprehensive()).isEqualTo(2_525_453L);
            assertThat(r.separate()).isEqualTo(3_559_896L);
            assertThat(r.decidedTax()).isEqualTo(3_559_890L);
            assertThat(r.choseComprehensive()).isFalse();

            // 다른 종합소득이 없으면 ② = 금융소득×14% 이고 그것이 곧 이미 낸 원천징수액이다.
            // "종합과세 대상이지만 더 내실 세금은 없습니다" — 결정세액만 답해서는 못 하는 문장.
            assertThat(r.payable()).isZero();
            assertThat(r.steps()).anyMatch(s -> s.contains("추가 납부하실 세액"));
        }

        @Test
        @DisplayName("richman — Gross-up 이 가능배당 쪽에서 잘린다 (M2·M3·M6)")
        void richman() {
            var r = tools.calculateTax(102_504_600L, 61_250_000L, 152_000_000L, 5_000_000L, 14_350_640L);

            // 초과액 82,504,600 보다 가능배당 61,250,000 이 작다 → min 은 가능배당 쪽에서 잘린다.
            assertThat(r.grossUp().targetAmount()).isEqualTo(61_250_000L);
            assertThat(r.grossUp().amount()).isEqualTo(6_125_000L);
            assertThat(r.taxableBase()).isEqualTo(235_629_600L);
            assertThat(r.comprehensive()).isEqualTo(66_274_248L);
            assertThat(r.separate()).isEqualTo(50_360_644L);
            assertThat(r.decidedTax()).isEqualTo(66_274_240L);
            assertThat(r.choseComprehensive()).isTrue();
            assertThat(r.localTax()).isEqualTo(6_627_424L);
            assertThat(r.payable()).isEqualTo(51_923_600L);
        }

        @Test
        @DisplayName("americano — 해외배당은 Gross-up 대상이 아니다 (M3)")
        void americano() {
            // 배당 27,440,000 이 전부 외국법인 배당이라 Gross-up 가능 배당은 0 이다.
            // "배당이 있으니 10% 가산"이라고 짜면 여기서 틀린다.
            var r = tools.calculateTax(30_356_350L, 0L, 61_300_000L, 1_500_000L, 4_249_880L);

            assertThat(r.grossUp().targetAmount()).isZero();
            assertThat(r.grossUp().amount()).isZero();
            assertThat(r.grossUp().dividendTaxCredit()).isZero();
            assertThat(r.decidedTax()).isEqualTo(13_877_520L);
            assertThat(r.choseComprehensive()).isTrue();
        }
    }

    // ------------------------------------------------------------------ 경계값

    @Nested
    @DisplayName("경계값 — 2,000만원은 '이하'가 분리과세다 (M4)")
    class Boundary {

        @ParameterizedTest(name = "금융소득 {0}원 → {1}")
        @CsvSource({
                "19990000, SEPARATE",
                "20000000, SEPARATE",   // 정확히 2,000만원이면 분리과세 (§14③6호 "2천만원 이하")
                "20000010, COMPREHENSIVE",
        })
        void 경계(long financialIncome, Taxation expected) {
            assertThat(tools.classifyTaxation(financialIncome, 0, 0, 0).taxation()).isEqualTo(expected);
        }

        @Test
        @DisplayName("시드 금액은 경계를 못 잡는다 — 합성 질문이 담당하는 이유")
        void 시드는_경계를_못_잡는다() {
            // 시드 금액은 마이데이터에서 오므로 20,000,000 처럼 딱 떨어질 수 없다.
            // 경계 검증은 시드가 아니라 이 합성 케이스의 몫이다. 결함이 아니라 역할 분담.
            assertThat(tools.classifyTaxation(15_259_710, 0, 0, 0).financialIncome())
                    .isNotEqualTo(20_000_000L);
        }
    }

    // ------------------------------------------------------------------ Gross-up

    @Nested
    @DisplayName("Gross-up — min 의 양쪽 가지가 모두 검증된다 (M2)")
    class GrossUpBranches {

        @Test
        @DisplayName("초과액 쪽에서 잘리는 경우 (jonber)")
        void 초과액_쪽() {
            var r = tools.calculateTax(25_427_830L, 24_180_000L, 0L, 1_500_000L, 0L);
            long excess = 25_427_830L - 20_000_000L;
            assertThat(r.grossUp().eligibleDividend()).isEqualTo(24_180_000L);
            assertThat(r.grossUp().targetAmount()).isEqualTo(excess).isEqualTo(5_427_830L);
        }

        @Test
        @DisplayName("가능배당 쪽에서 잘리는 경우 (richman)")
        void 가능배당_쪽() {
            var r = tools.calculateTax(102_504_600L, 61_250_000L, 152_000_000L, 5_000_000L, 0L);
            assertThat(r.grossUp().targetAmount()).isEqualTo(61_250_000L)
                    .isLessThan(102_504_600L - 20_000_000L);
        }

        @Test
        @DisplayName("배당가산율을 옛 11% 로 되돌리면 값이 달라진다 (M6)")
        void 가산율_11퍼센트() {
            var old = new TaxCalcTools(props(0.11));
            long correct = tools.calculateTax(102_504_600L, 61_250_000L, 152_000_000L, 5_000_000L, 0L).decidedTax();
            long wrong = old.calculateTax(102_504_600L, 61_250_000L, 152_000_000L, 5_000_000L, 0L).decidedTax();
            assertThat(wrong).isNotEqualTo(correct);
        }
    }

    // ------------------------------------------------------------------ 연중 플래닝

    @Nested
    @DisplayName("연중 플래닝 — 문턱이 두 종류다")
    class Simulation {

        @Test
        @DisplayName("분리과세 고객의 문턱은 과세 '방식'이 바뀌는 문턱이다")
        void 종합과세_진입() {
            var s = tools.simulateHeadroom(15_259_710L, 6_912_500L, 41_500_000L, 1_500_000L);

            assertThat(s.thresholdKind()).isEqualTo(TaxSimulation.Kind.COMPREHENSIVE_ENTRY);
            assertThat(s.headroom()).isEqualTo(4_740_290L);
            assertThat(s.crossed()).isFalse();
            assertThat(s.taxIfCrossed()).isPositive();
        }

        @Test
        @DisplayName("종합과세 고객의 문턱은 누진구간 경계다")
        void 누진구간_경계() {
            var s = tools.simulateHeadroom(102_504_600L, 61_250_000L, 152_000_000L, 5_000_000L);

            assertThat(s.thresholdKind()).isEqualTo(TaxSimulation.Kind.BRACKET_STEP);
            assertThat(s.nextThreshold()).isEqualTo(300_000_000L);
            assertThat(s.headroom()).isEqualTo(300_000_000L - 235_629_600L);
            assertThat(s.currentRate()).isEqualTo(0.38);
            assertThat(s.nextRate()).isEqualTo(0.40);
        }
    }

    // ------------------------------------------------------------------ 계산 전개

    @Test
    @DisplayName("steps 는 비어 있지 않고 마지막 줄이 실제 부담액과 일치한다")
    void 계산_전개() {
        TaxCalculation r = tools.calculateTax(102_504_600L, 61_250_000L, 152_000_000L, 5_000_000L, 14_350_640L);

        // 모델이 인용할 근거가 비어 있으면 도구가 숫자만 주는 것과 같아진다.
        assertThat(r.steps()).isNotEmpty();
        assertThat(r.steps()).anyMatch(s -> s.contains("소득세법 §15 2호"));
        assertThat(r.steps()).anyMatch(s -> s.contains("배당 전액이 아니다"));
        assertThat(r.steps().getLast()).contains("51,923,600");
    }

    @Test
    @DisplayName("부동소수점 오차가 세액으로 새지 않는다")
    void 부동소수점() {
        // 1_528_440 * 0.10 은 double 로는 152844.00000000001 이 된다.
        var r = tools.calculateTax(21_528_440L, 12_400_000L, 80_500_000L, 1_500_000L, 0L);
        assertThat(r.grossUp().amount()).isEqualTo(152_844L);
    }

    // ------------------------------------------------------------------ fixture

    /** 2026 귀속 세율표 — application.properties 와 같은 값이어야 한다 (실험 E11). */
    private static TaxDeskProperties props(double grossUpRate) {
        List<TaxDeskProperties.Bracket> brackets = List.of(
                new TaxDeskProperties.Bracket(14_000_000L, 0.06, 0L),
                new TaxDeskProperties.Bracket(50_000_000L, 0.15, 1_260_000L),
                new TaxDeskProperties.Bracket(88_000_000L, 0.24, 5_760_000L),
                new TaxDeskProperties.Bracket(150_000_000L, 0.35, 15_440_000L),
                new TaxDeskProperties.Bracket(300_000_000L, 0.38, 19_940_000L),
                new TaxDeskProperties.Bracket(500_000_000L, 0.40, 25_940_000L),
                new TaxDeskProperties.Bracket(1_000_000_000L, 0.42, 35_940_000L),
                new TaxDeskProperties.Bracket(0L, 0.45, 65_940_000L));
        return new TaxDeskProperties(2026, "소득세법 법률 제21221호 (시행 2026-07-01)", "2026-08-20",
                20_000_000L, grossUpRate, 0.14, 0.10, brackets,
                Map.of(), Map.of(), 2000, 20, 4, 0.25, 0.30, 100,
                "gpt-4o-mini", "gpt-4o-mini", "classpath:/tax-docs/");
    }
}
