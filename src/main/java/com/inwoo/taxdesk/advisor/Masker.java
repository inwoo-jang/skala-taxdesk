package com.inwoo.taxdesk.advisor;

import java.util.regex.Pattern;

/**
 * Phase 7 — 민감정보 마스킹. (practice3 {@code lab3/service/Masker} 를 증권사 도메인으로 확장)
 *
 * <p>practice3 대비 추가된 것이 <b>주민등록번호와 계좌번호</b>다.
 * 증권사 도메인은 이 둘이 로그나 응답에 평문으로 흘러다니면 그 자체로 사고다.
 *
 * <p>위치를 서비스가 아니라 {@code advisor/} 로 옮긴 이유: 마스킹은 업무 로직이 아니라
 * {@link SafetyAdvisor} 와 {@code ToolAuditAspect} 가 공유해 쓰는 도구다.
 *
 * <p><b>한계를 분명히 해 둔다.</b> 정규식 마스킹은 형식이 맞는 것만 잡는다.
 * "구공공일공일에 일이삼사오육칠" 같은 우회는 못 막는다. 이건 마지막 방어선이 아니라
 * <b>실수로 흘리는 것</b>을 막는 층이고, 진짜 방어는 애초에 그 데이터를 안 받는 설계다.
 */
public final class Masker {

    /** 주민등록번호 — 뒷자리 전체를 가린다. 성별 자리 하나만 남겨도 식별력이 크다. */
    private static final Pattern RRN = Pattern.compile("\\b(\\d{6})[-\\s]?([1-4]\\d{6})\\b");

    /**
     * 계좌번호 — 하이픈 포함 10~20자리. 앞 3자리와 뒤 2자리만 남긴다.
     * 앞뒤가 이미 가린 자리({@code -*})에 붙은 숫자는 건너뛴다 — 주민번호 앞자리
     * {@code 900101-*******} 를 계좌번호로 다시 쪼개 {@code 90-****-01-*******} 로 만들던 문제.
     */
    private static final Pattern ACCOUNT = Pattern.compile("(?<![\\d*-])\\b(\\d{2,4})-?(\\d{2,6})-?(\\d{2,8})\\b(?!-?\\*)");

    /** 카드번호 16자리. */
    private static final Pattern CARD = Pattern.compile("\\b(\\d{4})[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?(\\d{4})\\b");

    private static final Pattern EMAIL = Pattern.compile("\\b([\\w.+-]{1,3})[\\w.+-]*@([\\w.-]+)\\b");

    private static final Pattern PHONE = Pattern.compile("\\b(01[016-9])[-\\s]?(\\d{3,4})[-\\s]?(\\d{4})\\b");

    private Masker() {
    }

    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = text;
        // 순서가 중요하다. 주민번호를 먼저 잡지 않으면 계좌번호 패턴이 먼저 먹는다.
        s = RRN.matcher(s).replaceAll("$1-*******");
        s = CARD.matcher(s).replaceAll("$1-****-****-$2");
        s = PHONE.matcher(s).replaceAll("$1-****-$3");
        s = ACCOUNT.matcher(s).replaceAll("$1-****-$3");
        s = EMAIL.matcher(s).replaceAll("$1***@$2");
        return s;
    }

    /** 계좌번호처럼 이미 형식을 아는 값. 앞 3 · 뒤 2 만 남긴다. */
    public static String maskAccountNo(String accountNo) {
        if (accountNo == null || accountNo.length() <= 5) {
            return accountNo;
        }
        String digits = accountNo.replaceAll("[^0-9]", "");
        if (digits.length() <= 5) {
            return accountNo;
        }
        return digits.substring(0, 3) + "*".repeat(digits.length() - 5)
                + digits.substring(digits.length() - 2);
    }
}
