package com.inwoo.taxdesk.tools;

import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.domain.GrossUp;
import com.inwoo.taxdesk.domain.TaxCalculation;
import com.inwoo.taxdesk.domain.TaxClassification;
import com.inwoo.taxdesk.domain.TaxSimulation;
import com.inwoo.taxdesk.domain.Taxation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Phase 4 — 판정과 세액 계산. 이 실습의 하이라이트다.
 *
 * <p>도구가 <b>숫자만</b> 반환하면 모델이 그 숫자를 설명하다가 다시 산수를 한다.
 * 그래서 {@code steps[]}(계산 전개 과정)를 함께 반환하고, 모델은 그것을 인용만 하게 한다.
 * hallucination 을 "줄이는" 게 아니라 발생 지점 자체를 없애는 방식이다.
 *
 * <p><b>호출 순서를 코드가 강제한다.</b> {@link #classifyTaxation} 이 먼저고,
 * 종합과세로 판정된 경우에만 {@link #calculateTax} 다. 분리과세 고객에게 세액을 계산해 보여주면
 * 낼 필요 없는 세금을 안내하는 셈이라, {@code calculateTax} 는 기준금액 이하이면 <b>예외를 던진다.</b>
 * 이 순서는 우리가 정한 게 아니라 소득세법 §62 의 구조다.
 *
 * <p>비교과세: ① 종합과세 산출세액과 ② 분리과세 산출세액을 <b>둘 다</b> 구해 큰 값을 택한다
 * (소득세법 §15 2호 "비교하여 큰 금액"). 한쪽만 구해서 답하면 골든셋의 {@code jonber} 가 잡는다.
 *
 * <p>{@code externalAmount} 는 고객이 말한 타사 금융소득이다. 우리가 조회한 값이 아니므로
 * 계산 전개에도 "고객 제공"이라고 표시한다 — 어디까지가 확인된 값인지 답변에서 구분되어야 한다.
 *
 * <p>해외배당({@code IncomeType.FOREIGN_DIVIDEND})이 있으면 답변 말미에 <b>양도소득세 확인 안내</b>를
 * 붙인다. 해외주식 매도차익은 분류과세라 종합소득에 안 들어가지만(그래서 계산하지 않는다)
 * <b>별도로 신고 대상</b>이고 고객은 대개 이 둘을 헷갈린다.
 *
 * <p>결정세액에서 멈추지 않는다. 이자·배당은 지급 시점에 이미 14% 원천징수됐고 근로소득은
 * 연말정산으로 이미 냈으므로, 고객이 실제로 궁금한 것은 <b>추가로 낼 돈(또는 환급액)</b>이다.
 */
@Component
public class TaxCalcTools {

    private static final NumberFormat WON = NumberFormat.getInstance(Locale.KOREA);

    private final TaxDeskProperties props;

    public TaxCalcTools(TaxDeskProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ 판정

    /**
     * 분리과세로 끝나는지, 종합과세 대상인지 판정한다. <b>세액 계산보다 반드시 먼저 호출된다.</b>
     *
     * <p>기준금액 <b>"이하"가 분리과세</b>다(소득세법 §14③6호). 정확히 2,000만원이면 종합과세가
     * 아니라는 뜻이라, 판정은 "이상"이 아니라 <b>"초과"</b>여야 한다.
     */
    @Tool(description = """
            금융소득이 종합과세 대상인지 분리과세로 끝나는지 판정한다.
            세액 계산 전에 반드시 먼저 호출해야 한다. 분리과세로 판정되면 세액 계산은 하지 않는다.""")
    public TaxClassification classifyTaxation(
            @ToolParam(description = "과세대상 이자소득 (비과세·분리과세 상품 제외)") long interest,
            @ToolParam(description = "과세대상 내국법인 배당소득") long domesticDividend,
            @ToolParam(description = "과세대상 외국법인 배당소득") long foreignDividend,
            @ToolParam(description = "고객이 알려준 타사 금융소득. 모르면 0") long externalAmount) {

        long threshold = props.thresholdAmount();
        long financialIncome = interest + domesticDividend + foreignDividend + externalAmount;
        // "이하"가 분리과세이므로 판정은 초과. 여기서 >= 로 쓰면 경계값 테스트가 잡는다.
        boolean comprehensive = financialIncome > threshold;
        long excess = comprehensive ? financialIncome - threshold : 0L;

        List<String> steps = new ArrayList<>();
        steps.add("이자소득 %s원".formatted(won(interest)));
        steps.add("내국법인 배당 %s원".formatted(won(domesticDividend)));
        if (foreignDividend > 0) {
            steps.add("외국법인 배당 %s원".formatted(won(foreignDividend)));
        }
        if (externalAmount > 0) {
            steps.add("타사 금융소득 %s원 (고객 제공)".formatted(won(externalAmount)));
        }
        steps.add("과세대상 금융소득 합계 = %s원".formatted(won(financialIncome)));
        steps.add("종합과세기준금액 %s원 (소득세법 §14③6호)".formatted(won(threshold)));
        steps.add(comprehensive
                ? "기준 초과 %s원 → 종합과세 대상".formatted(won(excess))
                : ("기준 이하 → 금융소득은 분리과세로 종결. 원천징수 %s%%로 과세가 끝나며 "
                        + "금융소득만으로는 종합소득세 신고 대상이 아님 "
                        + "(근로·사업 등 다른 종합소득이 있으면 그 소득의 신고 의무는 별도)")
                        .formatted(rateText(props.separateTaxRate())));

        return new TaxClassification(comprehensive ? Taxation.COMPREHENSIVE : Taxation.SEPARATE,
                financialIncome, threshold, excess, List.copyOf(steps));
    }

    // ------------------------------------------------------------------ 세액

    /**
     * 비교과세로 결정세액을 구한다. <b>종합과세로 판정된 경우에만 호출한다.</b>
     *
     * @throws IllegalStateException 금융소득이 기준금액 이하일 때. 판정을 건너뛴 호출을 코드가 막는다
     */
    @Tool(description = """
            종합과세 대상 고객의 세액을 비교과세로 계산한다.
            반드시 classifyTaxation 으로 종합과세 판정을 받은 뒤에만 호출한다.
            계산 전개(steps)를 함께 반환하므로 답변에서는 그것을 인용만 하고 직접 계산하지 않는다.""")
    public TaxCalculation calculateTax(
            @ToolParam(description = "과세대상 금융소득 합계") long financialIncome,
            @ToolParam(description = "그중 Gross-up 가능한 내국법인 배당") long grossUpEligibleDividend,
            @ToolParam(description = "이자·배당을 제외한 종합소득금액 합계 (사업·근로·연금·기타). 고객 제공") long otherIncome,
            @ToolParam(description = "종합소득공제 합계. 고객 제공") long deduction,
            @ToolParam(description = "기납부세액 = 금융소득 원천징수 + 근로·사업 기납부분") long prepaidTax) {

        long threshold = props.thresholdAmount();
        if (financialIncome <= threshold) {
            throw new IllegalStateException(
                    "분리과세로 종결되는 금융소득(%s원)에는 세액을 계산하지 않는다. classifyTaxation 을 먼저 호출할 것"
                            .formatted(won(financialIncome)));
        }

        long excess = financialIncome - threshold;
        GrossUp grossUp = grossUp(grossUpEligibleDividend, excess);

        long totalIncome = excess + grossUp.amount() + otherIncome;
        long taxableBase = totalIncome - deduction;

        // ① 종합과세: 누진세율(과세표준) + 기준금액×14% − 배당세액공제
        long progressive = progressiveTax(taxableBase);
        long thresholdTax = pct(threshold, props.separateTaxRate());
        long comprehensive = progressive + thresholdTax - grossUp.dividendTaxCredit();

        // ② 분리과세: 금융소득×14% + 나머지 종합소득의 누진세율
        long financialFlat = pct(financialIncome, props.separateTaxRate());
        long otherProgressive = progressiveTax(otherIncome - deduction);
        long separate = financialFlat + otherProgressive;

        long decidedTax = floorTo10(Math.max(comprehensive, separate));
        long localTax = decidedTax / 10;              // 결정세액의 10% (지방세법)
        long payable = decidedTax - prepaidTax;       // 음수면 환급

        List<String> steps = new ArrayList<>();
        steps.add("금융소득 %s원 − 기준 %s원 = 초과 %s원".formatted(won(financialIncome), won(threshold), won(excess)));
        steps.add("Gross-up 가능 배당 %s원".formatted(won(grossUp.eligibleDividend())));
        steps.add("Gross-up 대상금액 = min(가능 배당, 초과액) = %s원  ← 배당 전액이 아니다 (§56④)"
                .formatted(won(grossUp.targetAmount())));
        steps.add("배당가산액 = %s원 × %s%% = %s원 (§17③)"
                .formatted(won(grossUp.targetAmount()), rateText(props.grossUpRate()), won(grossUp.amount())));
        steps.add("종합소득금액 = 초과 %s + 배당가산 %s + 그 밖의 종합소득 %s(고객 제공) = %s원"
                .formatted(won(excess), won(grossUp.amount()), won(otherIncome), won(totalIncome)));
        steps.add("과세표준 = %s − 소득공제 %s = %s원".formatted(won(totalIncome), won(deduction), won(taxableBase)));
        steps.add("① 종합과세 = 누진세율 %s + 기준금액 %s×%s%% %s − 배당세액공제 %s = %s원"
                .formatted(won(progressive), won(threshold), rateText(props.separateTaxRate()),
                        won(thresholdTax), won(grossUp.dividendTaxCredit()), won(comprehensive)));
        steps.add("② 분리과세 = 금융소득 %s×%s%% %s + 그 밖의 종합소득 누진세율 %s = %s원"
                .formatted(won(financialIncome), rateText(props.separateTaxRate()),
                        won(financialFlat), won(otherProgressive), won(separate)));
        steps.add("결정세액 = max(①, ②) = %s원  ← %s 선택 (소득세법 §15 2호)"
                .formatted(won(decidedTax), comprehensive >= separate ? "①" : "②"));
        steps.add("지방소득세 = 결정세액의 10%% = %s원 (별도 부과)".formatted(won(localTax)));
        steps.add("기납부세액 %s원 (원천징수 + 근로·사업 기납부분)".formatted(won(prepaidTax)));
        steps.add(payable >= 0
                ? "추가 납부하실 세액 = %s − %s = %s원".formatted(won(decidedTax), won(prepaidTax), won(payable))
                : "환급받으실 세액 = %s원".formatted(won(-payable)));

        return new TaxCalculation(List.copyOf(steps), taxableBase, comprehensive, separate,
                decidedTax, localTax, prepaidTax, payable, grossUp);
    }

    // ------------------------------------------------------------- 연중 플래닝

    /**
     * <b>연중 세무 플래닝</b> — "앞으로 얼마까지 더 받아도 되는가". 이 서비스의 핵심 기능이다.
     *
     * <p>다른 계산은 전부 순방향("얼마 받았으니 세금이 얼마")인데 이것만 역방향이다.
     * 8월에 결정세액을 알려주는 것은 이미 늦은 정보고, 고객은 <b>앞으로의 행동</b>을 정하려고 묻는다.
     *
     * <p>문턱이 두 종류라는 점이 중요하다. 분리과세 고객의 문턱은 <b>과세 방식</b>이 바뀌는 문턱이고,
     * 종합과세 고객의 문턱은 <b>세율만</b> 바뀌는 문턱이다. 전자가 훨씬 크게 튄다 —
     * 같은 문장으로 안내하면 고객이 심각도를 오해한다.
     */
    @Tool(description = """
            금융소득을 앞으로 얼마까지 더 받아도 세금 구조가 바뀌지 않는지 역산한다.
            연중에 배당·이자를 더 받을지 판단할 때 쓴다. 특정 종목이나 투자를 권유하지 않는다.""")
    public TaxSimulation simulateHeadroom(
            @ToolParam(description = "현재까지 확인된 과세대상 금융소득") long financialIncome,
            @ToolParam(description = "그중 Gross-up 가능한 내국법인 배당") long grossUpEligibleDividend,
            @ToolParam(description = "이자·배당을 제외한 종합소득금액 합계. 고객 제공") long otherIncome,
            @ToolParam(description = "종합소득공제 합계. 고객 제공") long deduction) {

        long threshold = props.thresholdAmount();

        // 아직 분리과세 — 문턱은 종합과세 진입선이다. 넘으면 과세 '방식'이 바뀐다.
        if (financialIncome <= threshold) {
            long headroom = threshold - financialIncome;
            long taxIfCrossed = calculateTax(threshold + 10, grossUpEligibleDividend,
                    otherIncome, deduction, 0L).decidedTax();
            return new TaxSimulation(financialIncome, threshold, headroom,
                    TaxSimulation.Kind.COMPREHENSIVE_ENTRY,
                    props.separateTaxRate(), bracketRate(0), taxIfCrossed);
        }

        // 이미 종합과세 — 문턱은 다음 누진구간이다. 세율만 바뀐다.
        TaxCalculation now = calculateTax(financialIncome, grossUpEligibleDividend,
                otherIncome, deduction, 0L);
        long base = now.taxableBase();
        TaxDeskProperties.Bracket current = bracketOf(base);
        if (current.isTop()) {
            return new TaxSimulation(financialIncome, 0L, 0L, TaxSimulation.Kind.NONE,
                    current.rate(), current.rate(), now.decidedTax());
        }
        long nextThreshold = current.upTo();
        long headroom = nextThreshold - base;
        double nextRate = bracketOf(nextThreshold + 1).rate();
        long taxIfCrossed = calculateTax(financialIncome + headroom + 1, grossUpEligibleDividend,
                otherIncome, deduction, 0L).decidedTax();
        return new TaxSimulation(financialIncome, nextThreshold, headroom,
                TaxSimulation.Kind.BRACKET_STEP, current.rate(), nextRate, taxIfCrossed);
    }

    // ------------------------------------------------------------------ 기한

    /** 신고·납부 기한. 2026 귀속이면 2027-05-31 이다. */
    @Tool(description = "해당 귀속연도의 종합소득세 확정신고·납부 기한을 알려준다")
    public DueDate estimateDueDate(@ToolParam(description = "귀속연도") int year) {
        LocalDate due = LocalDate.of(year + 1, 5, 31);
        return new DueDate(year, due,
                "%d년 귀속 종합소득세는 %d년 5월 31일까지 신고·납부합니다 (소득세법 §70)".formatted(year, year + 1));
    }

    public record DueDate(int year, LocalDate due, String note) {
    }

    // ------------------------------------------------------------------ 내부

    /**
     * 배당가산 4단계. {@code 배당 × 10%} 라는 필드 하나로 두면 반드시 틀린다 —
     * 대상은 <b>기준금액을 초과하는 부분</b>뿐이다(§56④).
     */
    private GrossUp grossUp(long eligibleDividend, long excess) {
        long target = Math.min(Math.max(eligibleDividend, 0L), Math.max(excess, 0L));
        long amount = pct(target, props.grossUpRate());
        return new GrossUp(eligibleDividend, target, amount, amount);
    }

    /** 누진세율 적용. 과세표준 × 세율 − 누진공제. */
    private long progressiveTax(long taxableBase) {
        if (taxableBase <= 0) {
            return 0L;
        }
        TaxDeskProperties.Bracket b = bracketOf(taxableBase);
        return pct(taxableBase, b.rate()) - b.deduction();
    }

    private TaxDeskProperties.Bracket bracketOf(long taxableBase) {
        for (TaxDeskProperties.Bracket b : props.brackets()) {
            if (taxableBase <= b.effectiveUpTo()) {
                return b;
            }
        }
        return props.brackets().getLast();
    }

    private double bracketRate(long taxableBase) {
        return bracketOf(Math.max(taxableBase, 1L)).rate();
    }

    /**
     * 금액 × 비율. {@code double} 곱셈은 {@code 1_528_440 * 0.10 = 152844.00000000001} 같은
     * 오차를 내고 그것이 그대로 세액 차이가 된다. 세금 계산에서 부동소수점을 그냥 쓰면 안 된다.
     */
    private static long pct(long amount, double rate) {
        return BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(rate))
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    /** 국고금 단수계산 — 세액은 10원 미만을 절사한다. */
    private static long floorTo10(long amount) {
        return amount / 10 * 10;
    }

    private static String won(long amount) {
        return WON.format(amount);
    }

    /**
     * 0.14 를 "14" 로. {@code rate * 100} 을 먼저 하면 안 된다 —
     * double 곱셈이 14.000000000000002 를 만들고 그게 그대로 고객 문구에 나간다.
     * BigDecimal 로 옮긴 뒤 곱한다.
     */
    private static String rateText(double rate) {
        return BigDecimal.valueOf(rate)
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros()
                .toPlainString();
    }
}
