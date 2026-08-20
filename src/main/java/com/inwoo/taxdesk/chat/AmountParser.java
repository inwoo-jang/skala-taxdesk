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
 */
public final class AmountParser {

    /** 근로소득금액 · 사업소득금액 등 "그 밖의 종합소득". 총급여는 일부러 제외한다. */
    private static final Pattern OTHER_INCOME = Pattern.compile(
            "(근로소득금?액?|사업소득금?액?|연금소득금?액?|기타소득금?액?|다른\\s*소득)\\s*(?:이|가|은|는)?\\s*([0-9억천만,\\s]+)\\s*원?");

    private static final Pattern DEDUCTION = Pattern.compile(
            "(소득공제|공제액?)\\s*(?:이|가|은|는)?\\s*([0-9억천만,\\s]+)\\s*원?");

    private static final Pattern PREPAID = Pattern.compile(
            "(결정세액|기납부|이미\\s*낸\\s*세금?|연말정산.{0,6}세액)\\s*(?:이|가|은|는)?\\s*([0-9억천만,\\s]+)\\s*원?");

    private static final Pattern EXTERNAL = Pattern.compile(
            "(타사|다른\\s*(?:증권사|은행)).{0,10}?([0-9억천만,\\s]+)\\s*원?");

    /** 총급여는 근로소득금액이 아니다. 그대로 쓰면 세액이 과대 산출된다. */
    private static final Pattern GROSS_PAY = Pattern.compile("총\\s*급여");

    private AmountParser() {
    }

    public static Long otherIncome(String text) {
        return find(OTHER_INCOME, text);
    }

    public static Long deduction(String text) {
        return find(DEDUCTION, text);
    }

    public static Long prepaidTax(String text) {
        return find(PREPAID, text);
    }

    public static Long externalAmount(String text) {
        return find(EXTERNAL, text);
    }

    /** 고객이 "총급여"라고 말했는가. 그렇다면 되물어야 한다 (문서 08 참조). */
    public static boolean mentionsGrossPay(String text) {
        return text != null && GROSS_PAY.matcher(text).find();
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
