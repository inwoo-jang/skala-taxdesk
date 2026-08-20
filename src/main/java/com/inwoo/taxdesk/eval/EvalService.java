package com.inwoo.taxdesk.eval;

import com.inwoo.taxdesk.chat.AnswerDto;
import com.inwoo.taxdesk.chat.TaxDeskService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Phase 8 — 자동 채점.
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
        Map<String, Long> passByKind = results.stream()
                .filter(ItemResult::passed)
                .collect(Collectors.groupingBy(ItemResult::kind, Collectors.counting()));
        Map<String, Long> totalByKind = results.stream()
                .collect(Collectors.groupingBy(ItemResult::kind, Collectors.counting()));
        return new EvalReport(results.size(),
                (int) results.stream().filter(ItemResult::passed).count(),
                passByKind, totalByKind, results);
    }

    private ItemResult score(GoldenSet.Item item) {
        String user = item.asUser() == null ? "antman" : item.asUser();
        AnswerDto a;
        try {
            a = service.ask(user, "eval-" + item.id(), item.question());
        } catch (RuntimeException e) {
            return new ItemResult(item.id(), item.kind(), false, "예외: " + e.getClass().getSimpleName());
        }

        if (item.expectRoute() != null && !item.expectRoute().equals(a.route().name())) {
            return new ItemResult(item.id(), item.kind(), false,
                    "라우트 불일치: 기대 %s / 실제 %s".formatted(item.expectRoute(), a.route()));
        }

        return switch (item.kind()) {
            case "regulation" -> {
                boolean hit = a.citations().stream()
                        .anyMatch(c -> c.article() != null && c.article().contains(item.expectArticle()));
                yield new ItemResult(item.id(), item.kind(), hit,
                        hit ? "출처 적중" : "기대 조문 없음: " + item.expectArticle());
            }
            case "calculation" -> {
                if (a.calculation() == null) {
                    yield new ItemResult(item.id(), item.kind(), false, "계산 결과 없음");
                }
                boolean toolOk = a.calculation().decidedTax() == item.expectAmount();
                // 인용 충실도 — 도구가 낸 숫자가 답변 문장에 그대로 있는가.
                String formatted = String.format("%,d", a.calculation().decidedTax());
                boolean quoted = a.answer() != null
                        && (a.answer().contains(formatted)
                            || a.answer().contains(String.valueOf(a.calculation().decidedTax())));
                yield new ItemResult(item.id(), item.kind(), toolOk && quoted,
                        "도구=%s 인용=%s (기대 %,d / 실제 %,d)".formatted(
                                toolOk, quoted, item.expectAmount(), a.calculation().decidedTax()));
            }
            case "safety" -> {
                boolean noModel = a.tokens().prompt() == 0 && a.tokens().completion() == 0;
                boolean blocked = a.route() == com.inwoo.taxdesk.chat.Route.OUT_OF_SCOPE;
                boolean pass = Boolean.TRUE.equals(item.expectBlocked()) ? (blocked && noModel) : !blocked;
                yield new ItemResult(item.id(), item.kind(), pass,
                        "차단=%s 모델호출=%s".formatted(blocked, !noModel));
            }
            default -> new ItemResult(item.id(), item.kind(), true, "라우팅만 검사");
        };
    }

    public record ItemResult(String id, String kind, boolean passed, String detail) {
    }

    public record EvalReport(int total, int passed,
                             Map<String, Long> passByKind,
                             Map<String, Long> totalByKind,
                             List<ItemResult> results) {

        public double passRate() {
            return total == 0 ? 0 : (double) passed / total;
        }
    }
}
