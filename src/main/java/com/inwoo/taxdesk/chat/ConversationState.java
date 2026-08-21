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
            Long other = AmountParser.otherIncome(text);
            // 되물은 직후라면 "5천만원이요" 처럼 항목명 없는 대답도 그 답으로 받는다.
            // 그러지 않으면 고객이 답을 했는데 못 알아듣고 같은 질문을 또 하게 된다.
            if (other == null && p.awaitingOtherIncome()) {
                other = AmountParser.bareAmount(text);
            }
            return new Facts(
                    other == null ? p.otherIncome() : other,
                    or(AmountParser.deduction(text), p.deduction()),
                    or(AmountParser.prepaidTax(text), p.prepaidTax()),
                    or(AmountParser.externalAmount(text), p.externalAmount()),
                    false);   // 되묻기는 한 턴짜리다. 답이 없으면 여기서 폐기된다
        });
    }

    /** 이번 턴에 되물었다고 표시한다. 다음 턴에서만 유효하다. */
    public void awaitOtherIncome(String conversationId) {
        byConversation.computeIfPresent(conversationId, (k, f) -> f.awaiting());
    }

    /** 콘솔 입력란처럼 고객이 직접 지정한 값. 문장 파싱보다 우선한다. */
    public Facts override(String conversationId, Long otherIncome, Long deduction, Long prepaidTax) {
        return byConversation.compute(conversationId, (k, prev) -> {
            Facts p = prev == null ? Facts.empty() : prev;
            return new Facts(
                    otherIncome == null ? p.otherIncome() : otherIncome,
                    deduction == null ? p.deduction() : deduction,
                    prepaidTax == null ? p.prepaidTax() : prepaidTax,
                    p.externalAmount(),
                    p.awaitingOtherIncome());
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
    public record Facts(Long otherIncome, long deduction, long prepaidTax, long externalAmount,
                        boolean awaitingOtherIncome) {

        public static Facts empty() {
            return new Facts(null, 0, 0, 0, false);
        }

        /**
         * <b>{@code null}(미상)과 {@code 0}(없다고 답함)은 다른 값이다.</b>
         * 둘을 0 으로 뭉치면 고객이 말한 적 없는데도 "다른 소득 0원"으로 계산하게 된다.
         */
        public boolean hasOtherIncome() {
            return otherIncome != null;
        }

        /** 계산에 넣을 값. 미상이면 부르는 쪽이 먼저 되물어야 한다. */
        public long otherIncomeOrZero() {
            return otherIncome == null ? 0L : otherIncome;
        }

        Facts awaiting() {
            return new Facts(otherIncome, deduction, prepaidTax, externalAmount, true);
        }
    }
}
