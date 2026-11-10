package com.inwoo.taxdesk.eval;

import com.inwoo.taxdesk.chat.AnswerDto;
import com.inwoo.taxdesk.chat.Route;
import com.inwoo.taxdesk.chat.TaxDeskService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Phase 8 — 자동 채점 (모델을 부르는 쪽).
 *
 * <p>practice3 대비 추가된 것은 <b>계산 채점기</b>다. 문장 유사도로는 세액이 맞았는지 알 수 없다.
 * 두 가지를 <b>따로</b> 본다.
 * <ol>
 *   <li><b>도구 정확도</b> — 도구가 반환한 결정세액 == 기대값</li>
 *   <li><b>인용 충실도</b> — 답변 문장에 그 숫자가 그대로 있는가 ← <b>이게 진짜 지표다</b></li>
 * </ol>
 * 2번이 틀리면 "도구는 맞았는데 모델이 다른 숫자를 말한" 경우이고, 그게 정확히 잡고 싶은 실패다.
 *
 * <p>{@code OUT_OF_SCOPE} 항목은 답변 내용이 아니라 <b>토큰 사용량</b>으로 채점한다.
 * 거절 문구가 훌륭해도 주 모델을 불렀으면 실패다.
 *
 * <p><b>통과율의 분모는 검토된 항목뿐이다.</b> 미검토 항목의 결과는 따로 보고하되 통과율에 넣지 않는다 —
 * 기대값이 검증되지 않은 항목의 "통과"는 아무것도 증명하지 않는다.
 */
@Service
public class EvalService {

    private final TaxDeskService service;

    public EvalService(TaxDeskService service) {
        this.service = service;
    }

    public EvalReport run(GoldenSet set) {
        List<ItemResult> results = new ArrayList<>();
        for (GoldenSet.Item item : set.items()) {
            results.add(score(item));
        }
        List<ItemResult> counted = results.stream().filter(ItemResult::counted).toList();
        Map<String, Long> passByKind = counted.stream()
                .filter(ItemResult::passed)
                .collect(Collectors.groupingBy(ItemResult::kind, Collectors.counting()));
        Map<String, Long> totalByKind = counted.stream()
                .collect(Collectors.groupingBy(ItemResult::kind, Collectors.counting()));
        return new EvalReport(results.size(),
                counted.size(),
                (int) counted.stream().filter(ItemResult::passed).count(),
                (int) results.stream().filter(r -> !r.counted()).count(),
                passByKind, totalByKind, results);
    }

    private ItemResult score(GoldenSet.Item item) {
        String user = item.asUser() == null ? "antman" : item.asUser();
        boolean counted = item.countsTowardPassRate();
        AnswerDto a;
        try {
            a = service.ask(user, "eval-" + item.id(), item.question());
        } catch (RuntimeException e) {
            return new ItemResult(item.id(), item.kind(), false, counted, "예외: " + e.getClass().getSimpleName());
        }

        if (item.expectRoute() != null && !item.expectRoute().equals(a.route().name())) {
            return new ItemResult(item.id(), item.kind(), false, counted,
                    "라우트 불일치: 기대 %s / 실제 %s".formatted(item.expectRoute(), a.route()));
        }

        return switch (item.kind()) {
            case "regulation" -> {
                // 검색된 것이 아니라 답변이 실제로 인용한 조문이어야 한다.
                boolean retrieved = a.citations().stream()
                        .anyMatch(c -> c.article() != null && c.article().contains(item.expectArticle()));
                boolean used = a.citations().stream()
                        .anyMatch(c -> c.used() && c.article() != null && c.article().contains(item.expectArticle()));
                yield new ItemResult(item.id(), item.kind(), used, counted,
                        used ? "출처 인용 적중" : retrieved ? "회수됐으나 답변이 인용하지 않음: " + item.expectArticle()
                                : "기대 조문 미회수: " + item.expectArticle());
            }
            case "calculation" -> {
                if (item.expectAmount() == null) {
                    boolean separate = a.calculation() == null
                            && a.toolCalls().stream().anyMatch(t -> "classifyTaxation".equals(t.name())
                                    && "SEPARATE".equals(t.result()));
                    yield new ItemResult(item.id(), item.kind(), separate, counted,
                            separate ? "분리과세 종결 · calculateTax 미호출" : "분리과세 기대인데 계산이 수행됨");
                }
                if (a.calculation() == null) {
                    yield new ItemResult(item.id(), item.kind(), false, counted, "계산 결과 없음");
                }
                boolean toolOk = a.calculation().decidedTax() == item.expectAmount();
                // 인용 충실도 — 도구가 낸 숫자가 답변 문장에 그대로 있는가.
                String formatted = String.format("%,d", a.calculation().decidedTax());
                boolean quoted = a.answer() != null
                        && (a.answer().contains(formatted)
                            || a.answer().contains(String.valueOf(a.calculation().decidedTax())));
                yield new ItemResult(item.id(), item.kind(), toolOk && quoted, counted,
                        "도구=%s 인용=%s (기대 %,d / 실제 %,d)".formatted(
                                toolOk, quoted, item.expectAmount(), a.calculation().decidedTax()));
            }
            case "safety" -> {
                boolean noModel = a.tokens().prompt() == 0 && a.tokens().completion() == 0;
                boolean blocked = a.route() == Route.OUT_OF_SCOPE;
                boolean pass = Boolean.TRUE.equals(item.expectBlocked()) ? (blocked && noModel) : !blocked;
                yield new ItemResult(item.id(), item.kind(), pass, counted,
                        "차단=%s 모델호출=%s".formatted(blocked, !noModel));
            }
            default -> new ItemResult(item.id(), item.kind(), true, counted, "라우팅만 검사");
        };
    }

    /**
     * @param counted 통과율 분모에 포함되는가 (검토된 기대값인가)
     */
    public record ItemResult(String id, String kind, boolean passed, boolean counted, String detail) {
    }

    /**
     * @param total      실행한 항목 수
     * @param counted    통과율 분모 (검토된 항목)
     * @param passed     분모 중 통과
     * @param unreviewed 미검토라 분모에서 뺀 항목 수
     */
    public record EvalReport(int total, int counted, int passed, int unreviewed,
                             Map<String, Long> passByKind,
                             Map<String, Long> totalByKind,
                             List<ItemResult> results) {

        /** 검토된 항목 기준 통과율. 분모가 0 이면 0 — "측정 안 됨"이지 "만점"이 아니다. */
        public double passRate() {
            return counted == 0 ? 0 : (double) passed / counted;
        }
    }
}
