package com.inwoo.taxdesk.chat;

import java.util.regex.Pattern;

/**
 * 쓰기 작업(신청) 미리보기에 대한 고객의 답을 읽는다.
 *
 * <p>모델에게 "고객이 동의했는가"를 묻지 않는다. 동의 여부는 저장소에 행을 만들지 말지를 가르는
 * 입력이라, 규칙으로 읽고 애매하면 <b>실행하지 않는</b> 쪽으로 기운다.
 * "네 그런데 ISA 는 뭔가요?" 처럼 질문이 섞이면 동의로 보지 않는다 — 새 질문으로 처리한다.
 */
final class Confirmation {

    private static final Pattern YES = Pattern.compile(
            "^\\s*(네|예|넵|응|그래|좋아요?|좋습니다|맞아요|신청(해|할게요|합니다|해\\s*주세요|해주세요)|"
                    + "진행(해|할게요|합니다|해\\s*주세요|해주세요)|확인(했어요|합니다|했습니다)|접수(해|합니다|해\\s*주세요))"
                    + "[\\s.!~요]*$");

    private static final Pattern NO = Pattern.compile(
            "^\\s*(아니요?|아뇨|아니오|괜찮아요|괜찮습니다|취소|취소(해|할게요|합니다|해\\s*주세요|해주세요)|"
                    + "안\\s*할게요|안\\s*합니다|하지\\s*마세요|됐어요|나중에)[\\s.!~요]*$");

    private Confirmation() {
    }

    static boolean isYes(String text) {
        return text != null && YES.matcher(text).matches();
    }

    static boolean isNo(String text) {
        return text != null && NO.matcher(text).matches();
    }
}
