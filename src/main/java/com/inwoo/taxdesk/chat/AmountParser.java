package com.inwoo.taxdesk.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 5 — 고객 문장에서 금액을 꺼낸다.
 *
 * <p>우리는 고객의 근로·사업소득을 모르므로 대화에서 받아야 한다.
 * 그런데 사람은 "1억 5천2백만원"이라고 말하지 {@code 152000000} 이라고 말하지 않는다.
 *
 * <p><b>모델에게 파싱을 맡기지 않는 이유</b>는 이 값이 세액 계산의 입력이기 때문이다.
 * 모델이 "5천만"을 5,000,000 으로 잘못 읽으면 세액이 통째로 틀리는데,
 * 그 오류는 답변 문장만 봐서는 보이지 않는다. 규칙으로 읽고, 못 읽으면 되묻는 편이 안전하다.
 *
 * <p><b>모든 값에서 "미상(null)"과 "0 이라고 답함(0)"을 구분한다.</b> 타사 금융소득도 마찬가지다 —
 * 고객이 타사 금액을 말한 적이 없는데 0 으로 놓고 "분리과세 종결"이라고 답하면,
 * 자사 0원을 전체 0원으로 단정하는 셈이다.
 */
public final class AmountParser {

    // 금액 그룹은 반드시 숫자로 시작해야 한다. [0-9억천만,\s]+ 로만 두면 공백 하나에도 매칭돼
    // 값이 0 으로 읽히고, 고객이 말한 금액이 조용히 사라진다.
    private static final String AMOUNT = "([0-9][0-9억천만,\\s]*)\\s*원?";

    /** 근로소득금액 · 사업소득금액 등 "그 밖의 종합소득". 총급여는 일부러 제외한다. */
    private static final Pattern OTHER_INCOME = Pattern.compile(
            "(근로소득금?액?|사업소득금?액?|연금소득금?액?|기타소득금?액?|다른\\s*소득)[^0-9]{0,8}" + AMOUNT);

    private static final Pattern DEDUCTION = Pattern.compile(
            "(소득공제|공제액?)[^0-9]{0,8}" + AMOUNT);

    private static final Pattern PREPAID = Pattern.compile(
            "(결정세액|기납부|이미\\s*낸\\s*세금?|연말정산.{0,6}세액)[^0-9]{0,8}" + AMOUNT);

    private static final Pattern EXTERNAL = Pattern.compile(
            "(타사|다른\\s*(?:증권사|은행|금융))[^0-9]{0,12}" + AMOUNT);

    /** "타사는 없어요" · "다른 은행 이자 없습니다" — 미상이 아니라 명시적 0 이다. */
    private static final Pattern NO_EXTERNAL = Pattern.compile(
            "(타사|다른\\s*(?:증권사|은행|금융|기관)|그\\s*외\\s*(?:금융)?소득)[^.!?]{0,14}(없|0원|영원)");

    /** 총급여는 근로소득금액이 아니다. 그대로 쓰면 세액이 과대 산출된다. */
    private static final Pattern GROSS_PAY = Pattern.compile("총\\s*급여|연봉");

    /** 매출·수입금액은 사업소득금액이 아니다 (필요경비를 빼기 전 값). 그대로 쓰면 과대 산출된다. */
    private static final Pattern SALES_REVENUE = Pattern.compile("매출(액)?|수입\\s*금액|총\\s*수입");

    private AmountParser() {
    }

    /** "없어요" · "0원" 도 답이다. 못 알아들으면 같은 질문을 영원히 반복하게 된다. */
    private static final Pattern NO_OTHER_INCOME = Pattern.compile(
            "(다른|그\\s*밖의|이자.{0,4}배당\\s*외).{0,8}(소득|수입).{0,6}(없|0원|영원)"
                    + "|(근로|사업|연금|기타)\\s*소득.{0,6}(없|0원)"
                    + "|^\\s*(없어요|없습니다|없음|없다|전혀\\s*없)");

    /** 되물은 다음 턴의 "5천만원이요" 처럼 항목명 없이 금액만 온 대답. */
    private static final Pattern BARE_AMOUNT = Pattern.compile(
            "^[^0-9억천만]{0,6}([0-9억천만,\\s]+)\\s*원?\\s*(?:이에?요|입니다|요|정도)?\\s*[.!]?$");

    public static Long otherIncome(String text) {
        if (text != null && NO_OTHER_INCOME.matcher(text.trim()).find()) {
            return 0L;   // 미상(null)이 아니라 "0 이라고 답했다"
        }
        return find(OTHER_INCOME, text);
    }

    /**
     * 그 밖의 종합소득의 <b>종류</b>. 세법상 소득금액은 종류별로 다른 서식 칸에서 오므로
     * 계산 전개에 "근로소득금액(고객 제공)"처럼 종류를 적어 두어야 고객이 어느 칸을 봤는지 확인할 수 있다.
     *
     * @return 근로 · 사업 · 연금 · 기타 중 하나. 문장에 종류가 없으면 {@code null}
     */
    public static String otherIncomeKind(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = OTHER_INCOME.matcher(text);
        if (!m.find()) {
            return null;
        }
        String head = m.group(1);
        if (head.startsWith("근로")) {
            return "근로";
        }
        if (head.startsWith("사업")) {
            return "사업";
        }
        if (head.startsWith("연금")) {
            return "연금";
        }
        if (head.startsWith("기타")) {
            return "기타";
        }
        return null;
    }

    /**
     * 항목명 없는 금액. <b>되물은 직후에만</b> 쓴다 —
     * 아무 때나 쓰면 "2천만원 넘으면 어떻게 되나요?"의 2천만원을 소득으로 읽는다.
     */
    public static Long bareAmount(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = BARE_AMOUNT.matcher(text.trim());
        if (!m.find()) {
            return null;
        }
        long v = parseKorean(m.group(1));
        return v > 0 ? v : null;
    }

    /**
     * 이번 턴이 <b>계산 입력값</b>을 담고 있는가. 담고 있으면 계산 경로로 보내야 한다.
     *
     * <p>"소득공제는 500만원이에요" 같은 진술은 질문처럼 안 생겨서 규정 질문으로 새기 쉽다.
     * 그러면 도구가 안 돌고 모델이 새 세액을 암산해 버린다.
     */
    public static boolean mentionsCalcInput(String text) {
        return text != null
                && (otherIncome(text) != null || deduction(text) != null
                        || prepaidTax(text) != null || externalAmount(text) != null);
    }

    public static Long deduction(String text) {
        return find(DEDUCTION, text);
    }

    public static Long prepaidTax(String text) {
        return find(PREPAID, text);
    }

    /**
     * 타사(미연동 기관) 금융소득. "타사는 없어요"는 {@code 0}, 말한 적 없으면 {@code null}.
     */
    public static Long externalAmount(String text) {
        if (text != null && NO_EXTERNAL.matcher(text).find() && find(EXTERNAL, text) == null) {
            return 0L;
        }
        return find(EXTERNAL, text);
    }

    /** 고객이 "총급여"라고 말했는가. 그렇다면 되물어야 한다 (문서 08 참조). */
    public static boolean mentionsGrossPay(String text) {
        return text != null && GROSS_PAY.matcher(text).find();
    }

    /** 고객이 "매출"·"수입금액"이라고 말했는가. 사업소득금액이 아니므로 되물어야 한다. */
    public static boolean mentionsSalesRevenue(String text) {
        return text != null && SALES_REVENUE.matcher(text).find();
    }

    private static Long find(Pattern p, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        long v = parseKorean(m.group(2));
        return v > 0 ? v : null;
    }

    /**
     * "1억 5천2백만", "8,000만원", "152000000" 을 모두 읽는다.
     *
     * <p>단위가 없는 순수 숫자는 그대로 원(圓)으로 본다 — "5000만원"과 "50000000"이
     * 같은 값이 되도록.
     */
    static long parseKorean(String raw) {
        if (raw == null) {
            return 0;
        }
        String s = raw.replaceAll("[,\\s]", "");
        if (s.isBlank()) {
            return 0;
        }
        if (s.matches("\\d+")) {
            return Long.parseLong(s);
        }
        long total = 0;
        long unitBase = 0;
        Matcher m = Pattern.compile("(\\d+)?(억|천만|백만|만|천|백)?").matcher(s);
        while (m.find()) {
            if (m.group(0).isEmpty()) {
                continue;
            }
            long n = m.group(1) == null ? 1 : Long.parseLong(m.group(1));
            String unit = m.group(2);
            if (unit == null) {
                total += unitBase > 0 ? n * (unitBase / 10) : n;
                continue;
            }
            long mult = switch (unit) {
                case "억" -> 100_000_000L;
                case "천만" -> 10_000_000L;
                case "백만" -> 1_000_000L;
                case "만" -> 10_000L;
                case "천" -> 1_000L;
                case "백" -> 100L;
                default -> 1L;
            };
            total += n * mult;
            unitBase = mult;
        }
        return total;
    }
}
