package com.inwoo.taxdesk.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Phase 5 — 대화별로 고객이 알려준 숫자를 기억한다.
 *
 * <p>{@code ChatMemory} 는 <b>문장</b>을 기억하지만 계산 입력을 기억하지는 못한다.
 * 3턴에서 "근로소득 5천만원"이라고 말하고 5턴에서 "그럼 세금은?"이라고 물으면,
 * 모델이 대화록을 다시 읽어 5천만원을 재추출해야 한다 — 그때 숫자를 잘못 읽으면 세액이 틀린다.
 *
 * <p>그래서 <b>숫자는 문장과 별도로 들고 있는다.</b> 모델이 다시 읽을 일이 없으면 다시 틀릴 일도 없다.
 */
@Component
public class ConversationState {

    private final Map<String, Facts> byConversation = new ConcurrentHashMap<>();

    public Facts get(String conversationId) {
        return byConversation.getOrDefault(conversationId, Facts.empty());
    }

    /** 이번 턴에 새로 말한 값만 덮어쓴다. 말하지 않은 값은 앞 턴 것을 유지한다. */
    public Facts merge(String conversationId, String text) {
        return byConversation.compute(conversationId, (k, prev) -> {
            Facts p = prev == null ? Facts.empty() : prev;
            return new Facts(
                    or(AmountParser.otherIncome(text), p.otherIncome()),
                    or(AmountParser.deduction(text), p.deduction()),
                    or(AmountParser.prepaidTax(text), p.prepaidTax()),
                    or(AmountParser.externalAmount(text), p.externalAmount()));
        });
    }

    public void clear(String conversationId) {
        byConversation.remove(conversationId);
    }

    private static long or(Long fresh, long previous) {
        return fresh == null ? previous : fresh;
    }

    /**
     * @param otherIncome    이자·배당을 제외한 종합소득금액 합계 (사업·근로·연금·기타)
     * @param deduction      종합소득공제 합계
     * @param prepaidTax     근로·사업 기납부세액 (연말정산 결정세액 등)
     * @param externalAmount 마이데이터 미연동 기관의 금융소득
     */
    public record Facts(long otherIncome, long deduction, long prepaidTax, long externalAmount) {

        public static Facts empty() {
            return new Facts(0, 0, 0, 0);
        }

        public boolean hasOtherIncome() {
            return otherIncome > 0;
        }
    }
}
