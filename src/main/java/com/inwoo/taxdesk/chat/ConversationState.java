package com.inwoo.taxdesk.chat;

import com.inwoo.taxdesk.domain.TaxSaverProduct;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Phase 5 — 대화별로 고객이 알려준 숫자와 진행 중인 작업을 기억한다.
 *
 * <p>{@code ChatMemory} 는 <b>문장</b>을 기억하지만 계산 입력을 기억하지는 못한다.
 * 3턴에서 "근로소득 5천만원"이라고 말하고 5턴에서 "그럼 세금은?"이라고 물으면,
 * 모델이 대화록을 다시 읽어 5천만원을 재추출해야 한다 — 그때 숫자를 잘못 읽으면 세액이 틀린다.
 *
 * <p>그래서 <b>숫자는 문장과 별도로 들고 있는다.</b> 모델이 다시 읽을 일이 없으면 다시 틀릴 일도 없다.
 *
 * <p>두 가지 "진행 중" 상태도 여기 둔다.
 * <ul>
 *   <li><b>되묻기</b> — 다른 종합소득을 물어본 뒤 답을 기다리는 중. 고객이 사이에 다른 질문을 해도
 *       답이 올 때까지 유지된다. 한 턴 만에 버리면 "아까 물어본 거 뭐였죠?"가 된다</li>
 *   <li><b>신청 미리보기</b> — 절세상품 자격확인 신청을 보여주고 확인을 기다리는 중.
 *       <b>쓰기 작업은 확인 없이 실행되지 않는다.</b> 다른 질문이 오면 미리보기는 만료된다 —
 *       쓰기는 되묻기와 달리 고객이 명시적으로 "예"라고 한 직후에만 실행해야 한다</li>
 * </ul>
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
            String kind = AmountParser.otherIncomeKind(text);
            // 되물은 뒤라면 "5천만원이요" 처럼 항목명 없는 대답도 그 답으로 받는다.
            // 그러지 않으면 고객이 답을 했는데 못 알아듣고 같은 질문을 또 하게 된다.
            if (other == null && p.awaitingOtherIncome()) {
                other = AmountParser.bareAmount(text);
            }
            Long external = AmountParser.externalAmount(text);
            return new Facts(
                    other == null ? p.otherIncome() : other,
                    other == null ? p.otherIncomeKind() : kind,
                    or(AmountParser.deduction(text), p.deduction()),
                    or(AmountParser.prepaidTax(text), p.prepaidTax()),
                    external == null ? p.externalAmount() : external,
                    // 답이 왔으면 되묻기는 끝난다. 안 왔으면 유지한다 — 다른 질문을 하고 돌아와도 이어진다.
                    p.awaitingOtherIncome() && other == null,
                    p.pendingAction());
        });
    }

    /** 이번 턴에 되물었다고 표시한다. 답이 올 때까지 유지된다. */
    public void awaitOtherIncome(String conversationId) {
        byConversation.compute(conversationId, (k, f) -> (f == null ? Facts.empty() : f).awaiting(true));
    }

    /** 콘솔 입력란처럼 고객이 직접 지정한 값. 문장 파싱보다 우선한다. */
    public Facts override(String conversationId, Long otherIncome, Long deduction, Long prepaidTax) {
        return byConversation.compute(conversationId, (k, prev) -> {
            Facts p = prev == null ? Facts.empty() : prev;
            return new Facts(
                    otherIncome == null ? p.otherIncome() : otherIncome,
                    otherIncome == null ? p.otherIncomeKind() : null,
                    deduction == null ? p.deduction() : deduction,
                    prepaidTax == null ? p.prepaidTax() : prepaidTax,
                    p.externalAmount(),
                    p.awaitingOtherIncome() && otherIncome == null,
                    p.pendingAction());
        });
    }

    /** 신청 미리보기를 걸어 둔다. 다음 턴의 명시적 확인에서만 실행된다. */
    public Facts proposeAction(String conversationId, TaxSaverProduct product, String reason) {
        return byConversation.compute(conversationId, (k, prev) ->
                (prev == null ? Facts.empty() : prev)
                        .withPending(new PendingAction(product, reason, Instant.now())));
    }

    /** 미리보기를 지운다 — 실행했든, 취소했든, 다른 질문으로 만료됐든. */
    public Facts clearAction(String conversationId) {
        return byConversation.compute(conversationId, (k, prev) ->
                (prev == null ? Facts.empty() : prev).withPending(null));
    }

    public void clear(String conversationId) {
        byConversation.remove(conversationId);
    }

    private static long or(Long fresh, long previous) {
        return fresh == null ? previous : fresh;
    }

    /** 확인을 기다리는 쓰기 작업. 실행 전까지 저장소에는 아무것도 남지 않는다. */
    public record PendingAction(TaxSaverProduct product, String reason, Instant proposedAt) {
    }

    /**
     * @param otherIncome        이자·배당을 제외한 종합소득금액 합계 (사업·근로·연금·기타). {@code null} = 미상
     * @param otherIncomeKind    그 소득의 종류 (근로/사업/연금/기타). 모르면 {@code null}
     * @param deduction          종합소득공제 합계
     * @param prepaidTax         근로·사업 기납부세액 (연말정산 결정세액 등)
     * @param externalAmount     마이데이터 미연동 기관의 금융소득. {@code null} = 미상, {@code 0} = 없다고 답함
     * @param awaitingOtherIncome 다른 종합소득을 되물은 뒤 답을 기다리는 중인가
     * @param pendingAction      확인을 기다리는 신청 미리보기
     */
    public record Facts(Long otherIncome, String otherIncomeKind, long deduction, long prepaidTax,
                        Long externalAmount, boolean awaitingOtherIncome, PendingAction pendingAction) {

        public static Facts empty() {
            return new Facts(null, null, 0, 0, null, false, null);
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

        /** 타사 금융소득을 알고 있는가 (0 포함). 모르면 판정은 "자사·연동 기준"이라는 단서가 붙는다. */
        public boolean hasExternalAmount() {
            return externalAmount != null;
        }

        public long externalAmountOrZero() {
            return externalAmount == null ? 0L : externalAmount;
        }

        public boolean hasPendingAction() {
            return pendingAction != null;
        }

        Facts awaiting(boolean on) {
            return new Facts(otherIncome, otherIncomeKind, deduction, prepaidTax, externalAmount, on, pendingAction);
        }

        Facts withPending(PendingAction action) {
            return new Facts(otherIncome, otherIncomeKind, deduction, prepaidTax, externalAmount,
                    awaitingOtherIncome, action);
        }
    }
}
