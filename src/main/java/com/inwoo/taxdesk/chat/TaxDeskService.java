package com.inwoo.taxdesk.chat;

import com.inwoo.taxdesk.advisor.UsageRegistry;
import com.inwoo.taxdesk.common.web.ModelUnavailableException;
import com.inwoo.taxdesk.config.AiConfig;
import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.domain.IncomeType;
import com.inwoo.taxdesk.domain.TaxCalculation;
import com.inwoo.taxdesk.domain.TaxClassification;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.tools.AccountIncomeTools;
import com.inwoo.taxdesk.tools.TaxCalcTools;
import com.inwoo.taxdesk.tools.ToolUser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Phase 3·5 — 업무 흐름의 중심.
 *
 * <p>{@link QueryRouter} 로 분기하고, 경로에 맞는 부품(RAG · Tool)을 부르고,
 * 근거와 계산을 {@link AnswerDto} 하나로 합친다. <b>모델 호출 여부 자체가 여기서 결정된다.</b>
 *
 * <p><b>HYBRID·SIMULATION 경로의 도구 호출 순서는 코드가 강제한다.</b> 모델에게 맡기지 않는다.
 * <pre>
 *   ① lookupMyIncome     자사·연동 금융소득 조회
 *   ② classifyTaxation   분리과세인가 종합과세인가          ← 먼저 판정
 *   ③ 분리과세면 끝. calculateTax 를 부르지 않는다
 *   ④ 종합과세일 때만 calculateTax 로 비교과세 ①② 전개
 * </pre>
 * 이 순서는 우리가 정한 게 아니라 <b>법 조문의 구조</b>다(소득세법 §62).
 * 판정 없이 세액부터 계산하면 분리과세 고객에게 낼 필요 없는 세금을 계산해 보여주게 된다.
 */
@Service
public class TaxDeskService {

    private static final Logger log = LoggerFactory.getLogger(TaxDeskService.class);
    /** 계산이 없는 답변(규정 안내·거절)에 붙는 기본 문구. */
    private static final String DISCLAIMER =
            "본 안내는 2026년 귀속 기준 예상 정보이며, 최종 판단은 세무 전문가와 상담하시기 바랍니다.";

    /**
     * 세액을 계산해 준 답변에 붙는 문구. <b>기본 문구보다 구체적이어야 한다.</b>
     *
     * <p>"참고만 하세요"라고만 하면 무엇이 달라질 수 있는지 알 수 없다.
     * 우리 계산이 실제와 달라지는 지점은 정해져 있다 — 우리가 모르는 값들이다.
     * <ul>
     *   <li><b>소득공제</b> — 인적공제·연금보험료·특별공제를 우리는 모른다</li>
     *   <li><b>세액공제·감면</b> — 배당세액공제 외의 공제와 그 법정 한도는 반영하지 않았다</li>
     *   <li><b>다른 종합소득</b> — 고객이 알려준 금액에 의존한다</li>
     * </ul>
     * 무엇을 모르는지 밝히는 것이 "참고용"이라는 말보다 정직하다.
     */
    private static final String CALC_DISCLAIMER = """
            본 금액은 SKALA증권이 확인한 금융소득과 고객님께서 알려주신 정보만으로 산출한 \
            2026년 귀속 예상 세액입니다. 실제 결정세액은 아래 요인에 따라 달라질 수 있으므로 \
            참고용으로만 활용해 주십시오.
            · 인적공제·연금보험료공제 등 종합소득공제 반영 여부
            · 배당세액공제 외의 세액공제·감면 및 법정 공제한도
            · 이자·배당 외 다른 종합소득의 실제 금액
            정확한 세액은 종합소득세 신고 시 확정되며, 최종 판단은 세무 전문가와 상담하시기 바랍니다.""";

    private final ChatClient chat;
    private final ChatClient fallback;
    private final QueryRouter router;
    private final AccountIncomeTools incomeTools;
    private final TaxCalcTools calcTools;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final UsageRegistry usage;
    private final ConversationState state;
    private final com.inwoo.taxdesk.tools.ProductApplicationTools applicationTools;
    private final TaxDeskProperties props;

    public TaxDeskService(@Qualifier("taxDeskChatClient") ChatClient chat,
                          @Qualifier("fallbackChatClient") ChatClient fallback,
                          QueryRouter router,
                          AccountIncomeTools incomeTools,
                          TaxCalcTools calcTools,
                          VectorStore vectorStore,
                          ChatMemory chatMemory,
                          UsageRegistry usage,
                          ConversationState state,
                          com.inwoo.taxdesk.tools.ProductApplicationTools applicationTools,
                          TaxDeskProperties props) {
        this.chat = chat;
        this.fallback = fallback;
        this.router = router;
        this.incomeTools = incomeTools;
        this.calcTools = calcTools;
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.usage = usage;
        this.state = state;
        this.applicationTools = applicationTools;
        this.props = props;
    }

    public AnswerDto ask(String userId, String conversationId, String question) {
        return ask(userId, conversationId, question, null, null, null);
    }

    public AnswerDto ask(String userId, String conversationId, String question,
                         Long otherIncome, Long deduction, Long prepaidTax) {
        long t0 = System.nanoTime();
        QueryRouter.Decision decision = router.route(question);

        // 고객이 이번 턴에 말한 숫자를 먼저 저장한다. 앞 턴 값은 유지된다.
        // 거절보다 앞에 둔다 — "타사 배당 1,000만원 더하면?" 은 거절 대상이지만
        // 그 1,000만원은 다음 턴에 합산해야 할 값이다. 거절이 먼저면 금액이 유실된다.
        ConversationState.Facts known = state.merge(conversationId, question);

        // 거절은 주 모델을 부르지 않는다. 토큰 0 이 이 설계의 증거이고, 응답에 그대로 드러난다.
        if (decision.refused()) {
            usage.record(userId, Route.OUT_OF_SCOPE.name(), 0, 0, ms(t0), false);
            return new AnswerDto(Route.OUT_OF_SCOPE, refusalMessage(decision.refusal()),
                    List.of(), List.of(), null, new AnswerDto.Tokens(0, 0), ms(t0), DISCLAIMER);
        }
        if (otherIncome != null || deduction != null || prepaidTax != null) {
            // 고객이 입력란에 직접 넣은 값. 문장에서 긁어낸 값보다 확실하므로 뒤에 덮어쓴다.
            known = state.override(conversationId, otherIncome, deduction, prepaidTax);
        }

        // 총급여는 근로소득금액이 아니다. 그대로 받으면 세액이 과대 산출되므로 되묻는다.
        if (AmountParser.mentionsGrossPay(question) && !known.hasOtherIncome()) {
            usage.record(userId, decision.route().name(), 0, 0, ms(t0), false);
            return new AnswerDto(decision.route(), GROSS_PAY_REASK,
                    List.of(), List.of(), null, new AnswerDto.Tokens(0, 0), ms(t0), DISCLAIMER);
        }

        List<AnswerDto.ToolCall> calls = new ArrayList<>();
        TaxCalculation calculation = null;
        String facts = "";

        if (decision.route() != Route.REGULATION) {
            Facts f = gather(userId, conversationId, decision.route(), known, calls);
            calculation = f.calculation();
            facts = f.text();
        }

        List<Document> docs =
                decision.route() == Route.CALCULATION ? List.of() : search(question);
        List<AnswerDto.Citation> citations = docs.stream()
                .map(TaxDeskService::toCitation).toList();

        // 도구 결과와 근거는 system 슬롯으로 보낸다. user 메시지는 고객이 실제로 친 문장이어야
        // 대화 기록(ChatMemory)에 조립된 프롬프트가 아니라 질문이 남는다.
        String context = compose(question, facts, docs);
        ChatResponse response = callModel(userId, conversationId, decision.route(), context, question);

        String answer = verifyCitations(response.getResult().getOutput().getText(), docs);
        var u = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        AnswerDto.Tokens tokens = new AnswerDto.Tokens(
                u == null || u.getPromptTokens() == null ? 0 : u.getPromptTokens(),
                u == null || u.getCompletionTokens() == null ? 0 : u.getCompletionTokens());

        return new AnswerDto(decision.route(), answer, citations, calls, calculation,
                tokens, ms(t0), calculation == null ? DISCLAIMER : CALC_DISCLAIMER);
    }

    /**
     * 도구를 코드가 정한 순서로 부른다. <b>모델이 순서를 정하게 두지 않는 이유</b>는
     * 분리과세 고객에게 세액을 계산해 보여주는 사고를 막기 위해서다.
     */
    private Facts gather(String userId, String conversationId, Route route,
                         ConversationState.Facts known, List<AnswerDto.ToolCall> calls) {
        ToolContext ctx = new ToolContext(ToolUser.contextOf(userId));
        StringBuilder sb = new StringBuilder();

        // ① 조회
        var income = incomeTools.lookupMyIncome(2026, ctx);
        calls.add(new AnswerDto.ToolCall("lookupMyIncome", "year=2026", summarize(income)));
        sb.append(summarize(income)).append('\n');

        // ② 판정 — 반드시 계산보다 먼저
        TaxClassification cls = calcTools.classifyTaxation(
                income.interest(), income.domesticDividend(), income.foreignDividend(),
                known.externalAmount());
        calls.add(new AnswerDto.ToolCall("classifyTaxation",
                "interest=%d, domestic=%d, foreign=%d".formatted(
                        income.interest(), income.domesticDividend(), income.foreignDividend()),
                cls.taxation().name()));
        sb.append(String.join(" / ", cls.steps())).append('\n');

        if (route == Route.ACTION) {
            // 도구는 PENDING 까지만 만든다. 승인·거절은 ADMIN 경로뿐이다(조특법 §88의2③).
            var receipt = applicationTools.requestEligibilityCheck(
                    com.inwoo.taxdesk.domain.TaxSaverProduct.TAX_FREE_SAVINGS,
                    "상담 중 절세상품 문의", 0L, ctx);
            calls.add(new AnswerDto.ToolCall("requestEligibilityCheck",
                    "product=TAX_FREE_SAVINGS", receipt.id() + " / " + receipt.status()));
            sb.append("자격 확인 신청이 접수되었습니다(접수번호 ").append(receipt.id())
              .append(", 상태 PENDING). ").append(receipt.note()).append('\n');
            return new Facts(sb.toString(), null);
        }
        if (route == Route.SIMULATION) {
            var sim = calcTools.simulateHeadroom(cls.financialIncome(),
                    income.domesticDividend(), known.otherIncomeOrZero(), known.deduction());
            calls.add(new AnswerDto.ToolCall("simulateHeadroom",
                    "financialIncome=%d".formatted(cls.financialIncome()), sim.thresholdKind().name()));
            sb.append(describe(sim));
            return new Facts(sb.toString(), null);
        }

        // ③ 분리과세면 여기서 끝. calculateTax 를 부르지 않는다.
        if (!cls.comprehensive()) {
            sb.append("분리과세로 종결되어 종합소득세 신고 대상이 아닙니다. 세액 계산을 수행하지 않았습니다.");
            return new Facts(sb.toString(), null);
        }

        // ④ 종합과세 — 다른 종합소득을 모르면 계산하지 않고 되묻는다.
        // 0 으로 놓고 계산한 뒤 "[전제] 0원으로 뒀습니다"를 붙이는 방식은 위험하다.
        // 모델이 그 한 줄을 빼먹으면 "추가 납부하실 세액 없음"만 남는다.
        if (!known.hasOtherIncome()) {
            state.awaitOtherIncome(conversationId);
            sb.append("종합과세 대상입니다. 다만 세액을 계산하려면 이자·배당 외 종합소득")
              .append("(근로·사업·연금·기타 소득금액)이 필요한데 아직 알려주시지 않았습니다.\n")
              .append("세액 계산을 수행하지 않았습니다. 고객에게 그 금액을 여쭤보세요. ")
              .append("해당 소득이 없으시면 \"없다\"고 답해 주시면 그대로 계산합니다.\n");
            return new Facts(sb.toString(), null);
        }

        TaxCalculation calc = calcTools.calculateTax(cls.financialIncome(),
                income.domesticDividend(), known.otherIncomeOrZero(), known.deduction(),
                income.withheldTax() + known.prepaidTax());
        calls.add(new AnswerDto.ToolCall("calculateTax",
                "financialIncome=%d, grossUpEligible=%d".formatted(
                        cls.financialIncome(), income.domesticDividend()),
                String.valueOf(calc.decidedTax())));
        sb.append(String.join("\n", calc.steps()));

        if (income.foreignDividend() > 0) {
            // 못 하는 것을 못 한다고 말하되, 놓치면 안 되는 것은 짚어준다.
            sb.append("\n[안내] 해외주식 매도차익이 있으면 양도소득세가 별도 신고 대상입니다. "
                    + "분류과세라 위 종합소득 계산에는 포함되지 않았습니다(소득세법 §14①).");
        }
        return new Facts(sb.toString(), calc);
    }

    /** 검색을 직접 수행한다. 어드바이저에 맡기면 그쪽 템플릿이 프롬프트를 통째로 바꾼다. */
    private List<Document> search(String question) {
        try {
            List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(props.ragTopK())
                    .similarityThreshold(props.ragSimilarityThreshold())
                    .build());
            return found == null ? List.of() : found;
        } catch (RuntimeException e) {
            log.warn("근거 검색 실패: {}", e.toString());
            return List.of();
        }
    }

    private static AnswerDto.Citation toCitation(Document d) {
        return new AnswerDto.Citation(
                String.valueOf(d.getMetadata().getOrDefault("doc", "")),
                String.valueOf(d.getMetadata().getOrDefault("article", "")),
                d.getScore() == null ? 0.0 : d.getScore());
    }

    /**
     * 프롬프트 조립. <b>도구 결과를 맨 앞에 두고 "확정된 사실"이라고 못 박는다.</b>
     *
     * <p>처음에는 "이 숫자만 사용하세요" 정도로 적었는데, 모델이 그 블록을 참고자료로 보고
     * 자기가 다시 계산하거나 "정보가 부족하다"고 답했다(BUILD-LOG Step 4).
     * 계산 결과는 참고자료가 아니라 <b>이미 확정된 답</b>이라는 것을 문장 수준에서 못 박아야 한다.
     */
    private String compose(String question, String facts, List<Document> docs) {  // NOSONAR
        StringBuilder sb = new StringBuilder();
        // 근거를 먼저, 고객 확정 사실을 나중에 넣는다.
        // 뒤집으면 모델이 법령 일반론으로 답을 채우고 고객 금액을 흘린다.
        if (!docs.isEmpty()) {
            sb.append("[근거 — 이 안에서만 세법 내용을 인용하세요]\n");
            docs.forEach(d -> sb.append("· 출처: ")
                    .append(d.getMetadata().getOrDefault("article", ""))
                    .append(" (").append(d.getMetadata().getOrDefault("effectiveYear", "")).append(" 귀속)\n")
                    .append(d.getText()).append("\n\n"));
        }
        if (!facts.isBlank()) {
            sb.append("""
                    ===== 확정 사실 (시스템이 이미 계산을 마친 결과) =====
                    아래 숫자는 고객 계좌 조회와 세법 계산으로 검증된 값입니다.
                    다시 계산하지 말고 그대로 인용해 설명만 하세요.
                    "정보가 부족하다", "계산할 수 없다"고 답하지 마세요. 필요한 값은 아래에 다 있습니다.
                    금액을 말할 때는 아래에 적힌 숫자를 그대로 옮겨 적으세요.
                    고객이 특정 계좌·상품을 지목해 물으면 법령 일반론으로 답하지 말고,
                    아래에서 그 상품의 금액과 과세처리를 찾아 금액을 밝혀 답하세요.

                    """).append(facts).append("\n\n");
            // 지시는 데이터 바로 뒤에 둔다. 앞머리에 두면 뒤따르는 법령 텍스트에 묻힌다.
            if (facts.contains("빠진 것:")) {
                sb.append("[지시] 고객이 그 상품을 지목해 물었습니다. 바로 위 \"빠진 것\" 줄의 "
                        + "항목을 하나도 빠뜨리지 말고 상품명과 금액을 답변에 그대로 옮겨 적으세요. "
                        + "그 금액은 고객이 실제로 받은 소득이지 법정 한도액이 아닙니다 — "
                        + "\"비과세 한도가 N원\"처럼 바꿔 쓰면 틀린 답이 됩니다. "
                        + "금액을 빼고 법령 일반론만 말하거나 \"금액이 확인되지 않는다\"고 답해서도 안 됩니다.\n\n");
            }
        }
        if (facts.contains("결정세액")) {
            // 모델이 답변 말미에 반드시 붙이도록 프롬프트에도 싣는다.
            // DTO 에만 담으면 채팅 화면에서 문구가 빠질 수 있다.
            sb.append("[답변 말미에 반드시 그대로 덧붙일 문구]\n").append(CALC_DISCLAIMER).append("\n\n");
        }
        return sb.toString();
    }

    private ChatResponse callModel(String userId, String conversationId, Route route,
                                   String context, String rawQuestion) {
        try {
            return chat.prompt()
                    .system(AiConfig.SYSTEM + "\n\n" + context)
                    .user(rawQuestion)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param(SafetyAdvisorKeys.RAW_QUESTION, rawQuestion)
                            .param("userId", userId)
                            .param("route", route.name()))
                    .toolContext(ToolUser.contextOf(userId))
                    .call()
                    .chatResponse();
        } catch (RuntimeException primary) {
            log.error("주 모델 실패, 폴백 시도: {}", primary.toString());
            try {
                return fallback.prompt().user(rawQuestion).call().chatResponse();
            } catch (RuntimeException secondary) {
                // 조용히 이상한 답을 내는 것이 최악이다. 503 으로 끝낸다.
                throw new ModelUnavailableException("주 모델과 폴백 모델이 모두 실패했습니다.", secondary);
            }
        }
    }

    /** 거절 문구. 갈래마다 근거 법령이 다르고, <b>합산 가능한 것에는 대안이 붙는다.</b> */
    private String refusalMessage(QueryRouter.Refusal refusal) {
        if (refusal == null) {
            return "죄송합니다. 저희 상담 범위를 벗어난 질문입니다.";
        }
        return switch (refusal) {
            case CLASSIFIED_INCOME -> """
                    양도소득·퇴직소득은 분류과세 대상이라 종합소득에 합산되지 않으며(소득세법 제14조 제1항),
                    저희 안내 범위가 아닙니다. 해당 세목은 국세상담센터(126)로 문의해 주세요.
                    저희는 이자·배당 등 금융소득만 안내드립니다.""";
            case OTHER_COMPREHENSIVE -> """
                    사업·근로·연금·기타소득의 금액은 저희가 계산해 드릴 수 없습니다.
                    다만 이미 알고 계신 소득금액을 알려주시면, 금융소득과 합산해
                    종합과세 판정과 예상 세액을 안내해 드리겠습니다.""";
            case NO_ACCESS -> """
                    조회 권한이 없어 해당 계좌는 확인해 드릴 수 없습니다.
                    마이데이터 연동을 추가하시거나, 금액을 알려주시면 합산해 계산해 드리겠습니다.""";
            case INVESTMENT_ADVICE -> """
                    종목 전망이나 매매 시점은 안내해 드릴 수 없습니다.
                    저희는 세무 안내 창구이며 투자자문업 등록 기관이 아닙니다.
                    다만 세제 관점에서 금융소득 기준까지 여유가 얼마인지는 안내해 드릴 수 있습니다.""";
            case TAX_AGENCY -> """
                    세무신고 대리는 세무사법 제2조상 세무사·세무법인만 할 수 있어 도와드릴 수 없습니다.
                    저희는 예상 안내까지만 가능하며, 필요하시면 절세상품 자격 확인을 신청해 드릴 수 있습니다.""";
        };
    }

    private static String summarize(AccountIncomeTools.IncomeSummary s) {
        String head = ("%d년 이자 %,d원 · 국내배당 %,d원 · 해외배당 %,d원 → 과세대상 금융소득 %,d원 "
                + "(비과세·분리과세 제외분 %,d원) / 원천징수 %,d원")
                .formatted(s.year(), s.interest(), s.domesticDividend(), s.foreignDividend(),
                        s.taxableTotal(), s.excludedTotal(), s.withheldTax());

        // 제외분은 총액이 아니라 상품명까지 싣는다. 도구가 items 로 목록을 주는데 여기서 버리면
        // "제 ISA 는 왜 빠졌나요?"에 댈 근거가 없어진다.
        // 종합과세 대상 행은 이미 위 합계에 있으므로 다시 싣지 않는다.
        String excluded = s.items().stream()
                .filter(i -> i.treatment() != TaxTreatment.TAXABLE)
                .map(i -> "[%s] 에서 고객이 실제로 받은 소득 %,d원 — %s"
                        .formatted(i.productName(), i.amount(), label(i.treatment())))
                .collect(Collectors.joining(" · "));
        return excluded.isEmpty() ? head
                : head + "\n고객님이 보유한 상품 중 종합과세 판정에서 빠진 것: " + excluded;
    }

    /** "세금이 없습니다"와 "9%로 과세가 끝났습니다"는 다른 말이다. 뭉치면 답변 문구가 틀린다. */
    private static String label(TaxTreatment t) {
        return switch (t) {
            case TAX_FREE -> "비과세 — 세금 자체가 없음";
            case SEPARATE -> "분리과세 — 별도 세율로 과세 종결, 종합소득에 합산 안 함";
            case TAXABLE -> "종합과세 대상";
        };
    }

    private static String describe(com.inwoo.taxdesk.domain.TaxSimulation sim) {
        return switch (sim.thresholdKind()) {
            case COMPREHENSIVE_ENTRY -> ("현재 분리과세로 종결됩니다. 금융소득을 %,d원 더 받으시면 "
                    + "종합과세로 전환되어 과세 방식 자체가 바뀝니다.").formatted(sim.headroom());
            case BRACKET_STEP -> ("이미 종합과세 대상입니다. 과세표준 기준 %,d원까지는 세율 %d%% 구간이 유지되고, "
                    + "넘으면 %d%% 구간으로 올라갑니다.").formatted(sim.headroom(),
                    Math.round(sim.currentRate() * 100), Math.round(sim.nextRate() * 100));
            case NONE -> "이미 최고 세율 구간이라 추가로 넘을 구간이 없습니다.";
        };
    }

    public void clearHistory(String conversationId) {
        chatMemory.clear(conversationId);
        state.clear(conversationId);
    }

    public List<String> history(String conversationId) {
        return chatMemory.get(conversationId).stream()
                .map(m -> m.getMessageType() + ": " + m.getText())
                .toList();
    }

    /** 총급여를 근로소득금액으로 오인하는 것은 실무에서 가장 흔한 오류다. 계산 대신 되묻는다. */
    private static final String GROSS_PAY_REASK = """
            종합소득에 합산되는 것은 총급여가 아니라 근로소득공제를 뺀 '근로소득금액'입니다. \
            총급여로 계산하면 세액이 실제보다 크게 나옵니다. \
            원천징수영수증의 '근로소득금액' 항목을 알려주시면 그 값으로 계산해 드리겠습니다. \
            본 안내는 예상치이며 최종 판단은 세무 전문가와 상담하시기 바랍니다.""";

    private static final Pattern CITED = Pattern.compile("\\[출처:\\s*([^\\](]+)");

    /**
     * 답변이 붙인 <code>[출처: …]</code> 가 실제 회수 결과에 있는 조문인지 확인한다.
     *
     * <p>프롬프트로 "근거에 없으면 지어내지 말라"고 요구했지만 지켜지지 않는 경우가 있었다.
     * 회수되지 않은 조문을 출처로 달면 형식상 정상 답변과 구분되지 않으므로,
     * 사람이 법령을 직접 찾아보기 전에는 틀린 줄 모른다. 그래서 코드가 대조한다.
     */
    private String verifyCitations(String answer, List<Document> docs) {
        return groundCitations(answer, docs.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault("article", "")))
                .toList());
    }

    /**
     * 답변이 회수되지 않은 조문을 인용하면 {@link #UNGROUNDED} 로 바꾼다.
     * 인자로만 판단하도록 떼어 두었다 — 이 규칙은 테스트로 지켜야 하기 때문이다.
     */
    static String groundCitations(String answer, List<String> retrievedArticles) {
        if (answer == null || retrievedArticles.isEmpty()) {
            return answer;
        }
        List<String> allowed = retrievedArticles.stream()
                .map(TaxDeskService::normalize)
                .filter(a -> !a.isBlank())
                .toList();
        if (allowed.isEmpty()) {
            return answer;
        }
        Matcher m = CITED.matcher(answer);
        while (m.find()) {
            String cited = normalize(m.group(1));
            if (cited.isBlank()) {
                continue;
            }
            boolean ok = allowed.stream().anyMatch(a -> a.contains(cited) || cited.contains(a));
            if (!ok) {
                log.warn("회수되지 않은 조문을 인용했다: {} (회수: {})", m.group(1).trim(), allowed);
                return UNGROUNDED;
            }
        }
        return answer;
    }

    /** 조문 표기가 "§17③"·"§ 17 ③"·"§17③ 각 호" 처럼 흔들리므로 공백과 귀속연도를 걷어낸다. */
    private static String normalize(String article) {
        return article.replaceAll("\\(.*?\\)", "").replaceAll("[\\s·]", "");
    }

    private static final String UNGROUNDED =
            "확인되지 않습니다. 국세상담센터(126)로 문의해 주세요. "
            + "본 안내는 예상치이며 최종 판단은 세무 전문가와 상담하시기 바랍니다.";

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private record Facts(String text, TaxCalculation calculation) {
    }

    @SuppressWarnings("unused")
    private static IncomeType unusedMarker() {
        return IncomeType.INTEREST;
    }
}
