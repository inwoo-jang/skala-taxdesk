package com.inwoo.taxdesk.chat;

import com.inwoo.taxdesk.advisor.UsageRegistry;
import com.inwoo.taxdesk.common.web.ModelUnavailableException;
import com.inwoo.taxdesk.config.AiConfig;
import com.inwoo.taxdesk.config.TaxDeskProperties;
import com.inwoo.taxdesk.domain.ApplicationStatus;
import com.inwoo.taxdesk.domain.TaxCalculation;
import com.inwoo.taxdesk.domain.TaxClassification;
import com.inwoo.taxdesk.domain.TaxSaverProduct;
import com.inwoo.taxdesk.domain.TaxTreatment;
import com.inwoo.taxdesk.rag.DocumentMeta;
import com.inwoo.taxdesk.tools.AccountIncomeTools;
import com.inwoo.taxdesk.tools.ProductApplicationTools;
import com.inwoo.taxdesk.tools.TaxCalcTools;
import com.inwoo.taxdesk.tools.ToolUser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Phase 3·5 — 업무 흐름의 중심.
 *
 * <p>{@link QueryRouter} 로 분기하고, 경로에 맞는 부품(RAG · Tool)을 부르고,
 * 근거와 계산을 {@link AnswerDto} 하나로 합친다. <b>모델 호출 여부 자체가 여기서 결정된다.</b>
 *
 * <p><b>HYBRID·SIMULATION 경로의 도구 호출 순서는 코드가 강제한다.</b> 모델에게 맡기지 않는다.
 * <pre>
 *   ① lookupMyIncome     자사·연동 금융소득 조회 (실패하면 판정하지 않는다)
 *   ② classifyTaxation   분리과세인가 종합과세인가          ← 먼저 판정
 *   ③ 분리과세면 끝. calculateTax 를 부르지 않는다
 *   ④ 종합과세일 때만 calculateTax 로 비교과세 ①② 전개
 * </pre>
 * 이 순서는 우리가 정한 게 아니라 <b>법 조문의 구조</b>다(소득세법 §62).
 * 판정 없이 세액부터 계산하면 분리과세 고객에게 낼 필요 없는 세금을 계산해 보여주게 된다.
 *
 * <p><b>모델을 부르지 않고 끝내는 경우</b>가 여럿이다. 전부 토큰 0 으로 응답에 드러난다.
 * <ul>
 *   <li>범위 밖 거절 · 되묻기(총급여 · 매출 · 다른 종합소득 미상)</li>
 *   <li>규칙 세트 버전이 검증되지 않았을 때 — 판정 보류</li>
 *   <li>계좌 조회가 실패했을 때 — 판정 보류 (자사 0원을 전체 0원으로 읽지 않는다)</li>
 *   <li>규정 질문인데 근거가 하나도 회수되지 않았을 때 — 답변 보류. 근거 없이 모델이 일반론을 말하게 두지 않는다</li>
 *   <li>신청 미리보기에 대한 확인·취소 — 쓰기는 모델을 거치지 않는다</li>
 * </ul>
 */
@Service
public class TaxDeskService {

    private static final Logger log = LoggerFactory.getLogger(TaxDeskService.class);

    private final ChatClient chat;
    private final ChatClient fallback;
    private final QueryRouter router;
    private final AccountIncomeTools incomeTools;
    private final TaxCalcTools calcTools;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final UsageRegistry usage;
    private final ConversationState state;
    private final ProductApplicationTools applicationTools;
    private final TaxDeskProperties props;

    /** 계산이 없는 답변(규정 안내·거절)에 붙는 기본 문구. */
    private final String disclaimer;

    /**
     * 세액을 계산해 준 답변에 붙는 문구. <b>기본 문구보다 구체적이어야 한다.</b>
     *
     * <p>"참고만 하세요"라고만 하면 무엇이 달라질 수 있는지 알 수 없다.
     * 우리 계산이 실제와 달라지는 지점은 정해져 있다 — 우리가 모르는 값들이다.
     * 무엇을 모르는지 밝히는 것이 "참고용"이라는 말보다 정직하다.
     */
    private final String calcDisclaimer;

    public TaxDeskService(@Qualifier("taxDeskChatClient") ChatClient chat,
                          @Qualifier("fallbackChatClient") ChatClient fallback,
                          QueryRouter router,
                          AccountIncomeTools incomeTools,
                          TaxCalcTools calcTools,
                          VectorStore vectorStore,
                          ChatMemory chatMemory,
                          UsageRegistry usage,
                          ConversationState state,
                          ProductApplicationTools applicationTools,
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
        this.disclaimer = ("본 안내는 %d년 귀속 기준 실습용 예상 정보이며 법률·세무 자문이 아닙니다. "
                + "최종 판단은 세무 전문가와 상담하시기 바랍니다.").formatted(props.taxYear());
        this.calcDisclaimer = """
                본 금액은 SKALA증권이 확인한 금융소득과 고객님께서 알려주신 정보만으로 산출한 \
                %d년 귀속 실습용 예상 세액입니다. 실제 결정세액은 아래 요인에 따라 달라질 수 있으므로 \
                참고용으로만 활용해 주십시오.
                · 인적공제·연금보험료공제 등 종합소득공제 반영 여부
                · 배당세액공제 외의 세액공제·감면 및 법정 공제한도
                · 이자·배당 외 다른 종합소득의 실제 금액과 미연동 기관의 금융소득
                · 고배당기업 배당소득 특례(조특법 §104의27) 등 신청이 필요한 특례의 적용 여부
                정확한 세액은 종합소득세 신고 시 확정되며, 최종 판단은 세무 전문가와 상담하시기 바랍니다."""
                .formatted(props.taxYear());
    }

    public AnswerDto ask(String userId, String conversationId, String question) {
        return ask(userId, conversationId, question, null, null, null);
    }

    public AnswerDto ask(String userId, String conversationId, String question,
                         Long otherIncome, Long deduction, Long prepaidTax) {
        long t0 = System.nanoTime();
        List<String> notices = new ArrayList<>();

        // 규칙 세트에 버전이 없거나 세율표가 깨져 있으면 아무 판정도 하지 않는다.
        if (!props.rulesUsable()) {
            usage.record(userId, Route.REGULATION.name(), 0, 0, ms(t0), false);
            notices.add("적용할 세법 규칙의 버전이 검증되지 않아 판정과 계산을 보류합니다.");
            notices.addAll(props.problems());
            return plain(Route.REGULATION, RULES_UNAVAILABLE, notices, t0);
        }

        // 확인을 기다리는 쓰기 작업이 있으면 그 답부터 처리한다. 쓰기는 모델을 거치지 않는다.
        ConversationState.Facts before = state.get(conversationId);
        if (before.hasPendingAction()) {
            AnswerDto handled = resolvePendingAction(userId, conversationId, question, before, t0);
            if (handled != null) {
                return handled;
            }
            notices.add("이전에 보여드린 신청 미리보기는 실행되지 않고 만료되었습니다. 다시 원하시면 말씀해 주세요.");
        }

        QueryRouter.Decision decision = router.route(question);

        // 고객이 이번 턴에 말한 숫자를 먼저 저장한다. 앞 턴 값은 유지된다.
        // 거절보다 앞에 둔다 — "타사 배당 1,000만원 더하면?" 은 거절 대상이지만
        // 그 1,000만원은 다음 턴에 합산해야 할 값이다. 거절이 먼저면 금액이 유실된다.
        ConversationState.Facts known = state.merge(conversationId, question);

        // 거절은 주 모델을 부르지 않는다. 토큰 0 이 이 설계의 증거이고, 응답에 그대로 드러난다.
        if (decision.refused()) {
            usage.record(userId, Route.OUT_OF_SCOPE.name(), 0, 0, ms(t0), false);
            return plain(Route.OUT_OF_SCOPE, withReminder(refusalMessage(decision.refusal()), known), notices, t0);
        }
        if (otherIncome != null || deduction != null || prepaidTax != null) {
            // 고객이 입력란에 직접 넣은 값. 문장에서 긁어낸 값보다 확실하므로 뒤에 덮어쓴다.
            known = state.override(conversationId, otherIncome, deduction, prepaidTax);
        }

        // 총급여·매출은 소득금액이 아니다. 그대로 받으면 세액이 과대 산출되므로 되묻는다.
        if (!known.hasOtherIncome()) {
            if (AmountParser.mentionsGrossPay(question)) {
                usage.record(userId, decision.route().name(), 0, 0, ms(t0), false);
                return plain(decision.route(), GROSS_PAY_REASK, notices, t0);
            }
            if (AmountParser.mentionsSalesRevenue(question)) {
                usage.record(userId, decision.route().name(), 0, 0, ms(t0), false);
                return plain(decision.route(), SALES_REASK, notices, t0);
            }
        }

        List<AnswerDto.ToolCall> calls = new ArrayList<>();
        Facts f = Facts.none();
        if (decision.route() != Route.REGULATION) {
            try {
                f = gather(userId, conversationId, decision.route(), question, known, calls, notices);
            } catch (DataAccessException e) {
                // 조회가 실패했으면 "0원"이 아니라 "모른다"다. 판정하지 않는다.
                log.error("계좌 조회 실패 — 판정 보류: {}", e.toString());
                usage.record(userId, decision.route().name(), 0, 0, ms(t0), false);
                notices.add("계좌 조회에 실패해 금융소득을 확인하지 못했습니다. 판정과 계산을 보류합니다.");
                return plain(decision.route(), LOOKUP_FAILED, notices, t0);
            }
            if (f.holdWithoutModel()) {
                usage.record(userId, decision.route().name(), 0, 0, ms(t0), false);
                return new AnswerDto(decision.route(), withReminder(f.text(), known), List.of(), calls, null,
                        f.overview(), List.copyOf(notices), f.pendingAction(),
                        new AnswerDto.Tokens(0, 0), ms(t0), disclaimer, props.ruleLabel());
            }
        }

        Search search = decision.route() == Route.CALCULATION ? Search.none() : search(question);
        if (search.failed()) {
            notices.add("근거 검색에 장애가 있어 법령 근거 없이 안내합니다. 세법 내용은 별도로 확인해 주세요.");
        }
        if (decision.route() == Route.REGULATION && search.docs().isEmpty()) {
            // 근거가 없으면 모델이 일반론을 말하게 두지 않는다. 보류가 답이다.
            usage.record(userId, decision.route().name(), 0, 0, ms(t0), false);
            notices.add(search.failed() ? "검색 장애로 근거를 확인하지 못했습니다."
                    : "현재 보유한 %d년 귀속 안내 문서에서 관련 근거를 찾지 못했습니다.".formatted(props.taxYear()));
            return plain(Route.REGULATION, withReminder(NO_EVIDENCE, known), notices, t0);
        }

        // 도구 결과와 근거는 system 슬롯으로 보낸다. user 메시지는 고객이 실제로 친 문장이어야
        // 대화 기록(ChatMemory)에 조립된 프롬프트가 아니라 질문이 남는다.
        String context = compose(f.text(), search.docs(), f.calculation() != null);
        ChatResponse response = callModel(userId, conversationId, decision.route(), context, question);

        String raw = response.getResult().getOutput().getText();
        String answer = groundCitations(raw, articlesOf(search.docs()), lawArticlesIn(f.text()));
        if (!answer.equals(raw)) {
            notices.add("답변이 회수되지 않은 조문을 인용해 내보내지 않았습니다.");
        }
        answer = withReminder(answer, known);

        var u = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        AnswerDto.Tokens tokens = new AnswerDto.Tokens(
                u == null || u.getPromptTokens() == null ? 0 : u.getPromptTokens(),
                u == null || u.getCompletionTokens() == null ? 0 : u.getCompletionTokens());

        return new AnswerDto(decision.route(), answer, toCitations(search.docs(), answer), calls,
                f.calculation(), f.overview(), List.copyOf(notices), f.pendingAction(),
                tokens, ms(t0), f.calculation() == null ? disclaimer : calcDisclaimer, props.ruleLabel());
    }

    // ------------------------------------------------------------------ 쓰기 확인

    /**
     * 미리보기에 대한 "예/아니오"를 처리한다. 둘 다 아니면 {@code null} — 미리보기는 만료되고
     * 이번 문장은 새 질문으로 흐른다. <b>애매하면 실행하지 않는다.</b>
     */
    private AnswerDto resolvePendingAction(String userId, String conversationId, String question,
                                           ConversationState.Facts before, long t0) {
        ConversationState.PendingAction pending = before.pendingAction();
        List<String> notices = new ArrayList<>();
        if (Confirmation.isNo(question)) {
            state.clearAction(conversationId);
            usage.record(userId, Route.ACTION.name(), 0, 0, ms(t0), false);
            return plain(Route.ACTION, "알겠습니다. %s 자격 확인 신청은 접수하지 않았습니다."
                    .formatted(label(pending.product())), notices, t0);
        }
        if (!Confirmation.isYes(question)) {
            state.clearAction(conversationId);
            return null;
        }
        state.clearAction(conversationId);
        ToolContext ctx = new ToolContext(ToolUser.contextOf(userId));
        List<AnswerDto.ToolCall> calls = new ArrayList<>();
        String text;
        try {
            var duplicate = findPending(ctx, pending.product());
            if (duplicate != null) {
                notices.add("같은 상품의 자격 확인 신청(접수번호 " + duplicate + ")이 이미 PENDING 상태라 다시 접수하지 않았습니다.");
                text = "이미 접수된 %s 자격 확인 건(접수번호 %s)이 처리 대기 중입니다. 중복으로 접수하지 않았습니다."
                        .formatted(label(pending.product()), duplicate);
            } else {
                var receipt = applicationTools.requestEligibilityCheck(pending.product(), pending.reason(), 0L, ctx);
                calls.add(new AnswerDto.ToolCall("requestEligibilityCheck",
                        "product=" + pending.product(), receipt.id() + " / " + receipt.status()));
                text = ("%s 자격 확인 신청을 접수했습니다(접수번호 %s, 상태 %s). %s\n"
                        + "※ 이 접수는 실습용 내부 기록이며 실제 상품 가입 시스템과 연결되어 있지 않습니다.")
                        .formatted(label(pending.product()), receipt.id(), receipt.status(), receipt.note());
            }
        } catch (RuntimeException e) {
            log.error("자격 확인 신청 접수 실패: {}", e.toString());
            notices.add("신청 접수 중 오류가 발생해 접수되지 않았습니다. 잠시 후 다시 시도해 주세요.");
            text = "죄송합니다. 신청 접수 중 오류가 발생해 접수되지 않았습니다. 잠시 후 다시 말씀해 주세요.";
        }
        usage.record(userId, Route.ACTION.name(), 0, 0, ms(t0), false);
        return new AnswerDto(Route.ACTION, text, List.of(), calls, null, null, notices, null,
                new AnswerDto.Tokens(0, 0), ms(t0), disclaimer, props.ruleLabel());
    }

    private String findPending(ToolContext ctx, TaxSaverProduct product) {
        return applicationTools.listMyApplications(ctx).stream()
                .filter(a -> a.product() == product && a.status() == ApplicationStatus.PENDING)
                .map(ProductApplicationTools.ApplicationView::id)
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ 도구 호출

    /**
     * 도구를 코드가 정한 순서로 부른다. <b>모델이 순서를 정하게 두지 않는 이유</b>는
     * 분리과세 고객에게 세액을 계산해 보여주는 사고를 막기 위해서다.
     */
    private Facts gather(String userId, String conversationId, Route route, String question,
                         ConversationState.Facts known, List<AnswerDto.ToolCall> calls, List<String> notices) {
        ToolContext ctx = new ToolContext(ToolUser.contextOf(userId));
        StringBuilder sb = new StringBuilder();
        int year = props.taxYear();

        // ① 조회 — 실패하면 DataAccessException 이 그대로 올라가 호출자가 보류한다.
        var income = incomeTools.lookupMyIncome(year, ctx);
        var overview = incomeTools.overview(year, ctx, known.externalAmount());
        calls.add(new AnswerDto.ToolCall("lookupMyIncome", "year=" + year, summarize(income)));
        sb.append(summarize(income)).append('\n');

        // 수기 입력이 이미 연동된 기관과 겹치는지 — 겹치면 두 번 더해진다.
        Long declaredNow = AmountParser.externalAmount(question);
        if (declaredNow != null && declaredNow > 0) {
            for (String inst : overview.linkedInstitutions()) {
                if (question.contains(inst)) {
                    notices.add(inst + " 은(는) 이미 마이데이터로 연동되어 금액이 반영돼 있습니다. "
                            + "말씀하신 금액이 같은 소득이면 중복 합산됩니다. 다른 소득인지 확인해 주세요.");
                    sb.append("[주의] 고객이 말한 타사 금액의 기관(").append(inst)
                      .append(")은 이미 연동된 기관이다. 중복 가능성을 고객에게 확인하라.\n");
                }
            }
        }

        // ② 판정 — 반드시 계산보다 먼저
        TaxClassification cls = calcTools.classifyTaxation(
                income.interest(), income.domesticDividend(), income.foreignDividend(),
                known.externalAmountOrZero());
        calls.add(new AnswerDto.ToolCall("classifyTaxation",
                "interest=%d, domestic=%d, foreign=%d, external=%s".formatted(
                        income.interest(), income.domesticDividend(), income.foreignDividend(),
                        known.hasExternalAmount() ? String.valueOf(known.externalAmount()) : "미확인"),
                cls.taxation().name()));
        sb.append(String.join(" / ", cls.steps())).append('\n');

        // 판정의 단서. 모델이 "신고 안 하셔도 됩니다"로 단정하지 못하게 사실로 못 박는다.
        if (!known.hasExternalAmount()) {
            notices.add("미연동 기관(타사)의 금융소득은 확인되지 않았습니다. 위 판정은 자사·연동 기관 자료 기준이며, "
                    + "타사 소득이 있으면 결과가 달라질 수 있습니다.");
            sb.append("[단서] 타사(미연동 기관) 금융소득은 미확인이다. 판정은 자사·연동 자료 기준임을 반드시 말하고, "
                    + "타사 소득이 있으면 알려달라고 안내하라. \"신고 의무가 전혀 없다\"고 단정하지 마라.\n");
        } else if (known.externalAmountOrZero() == 0) {
            sb.append("[확인] 고객이 타사 금융소득은 없다고 답했다.\n");
        }
        if (income.domesticDividend() > 0) {
            notices.add("고배당기업 배당소득 특례(조특법 §104의27)는 대상 여부와 합산배제 신청 여부가 확인되지 않아 반영하지 않았습니다.");
        }
        if (income.excludedTotal() > 0) {
            notices.add("비과세·분리과세로 분류된 %,d원은 판정에서 제외했습니다. 분류는 계좌 자료의 상품 구분을 따랐으며 상품별 요건을 별도 검증하지는 않았습니다."
                    .formatted(income.excludedTotal()));
        }
        notices.add("%d년 귀속 · 조회 시점까지 확인된 지급분 기준입니다. 연말까지 추가 지급이 있으면 달라집니다.".formatted(year));

        if (route == Route.ACTION) {
            return proposeAction(conversationId, ctx, question, sb, overview, notices);
        }
        if (route == Route.SIMULATION) {
            var sim = calcTools.simulateHeadroom(cls.financialIncome(),
                    income.domesticDividend(), known.otherIncomeOrZero(), known.deduction());
            calls.add(new AnswerDto.ToolCall("simulateHeadroom",
                    "financialIncome=%d".formatted(cls.financialIncome()), sim.thresholdKind().name()));
            sb.append(describe(sim));
            if (!known.hasOtherIncome()) {
                sb.append(" (다른 종합소득은 미상이라 0 으로 두고 문턱만 계산했다. 세율 구간 문턱은 그 값에 따라 달라진다.)");
                notices.add("다른 종합소득이 미확인이라 세율 구간 문턱은 0원 가정으로 계산했습니다.");
            }
            return new Facts(sb.toString(), null, overview, null, false);
        }
        if (route == Route.CALCULATION && DUE_DATE.matcher(question).find()) {
            var due = calcTools.estimateDueDate(year);
            calls.add(new AnswerDto.ToolCall("estimateDueDate", "year=" + year, due.due().toString()));
            sb.append(due.note()).append('\n');
        }

        // ③ 분리과세면 여기서 끝. calculateTax 를 부르지 않는다.
        if (!cls.comprehensive()) {
            sb.append("금융소득은 분리과세로 종결되어 금융소득만으로는 종합소득세 신고 대상이 아니다. 세액 계산을 수행하지 않았다. ")
              .append("다른 종합소득(근로·사업 등)이 있으면 그 소득의 신고 의무는 별도라는 점을 덧붙여라.");
            return new Facts(sb.toString(), null, overview, null, false);
        }

        // ④ 종합과세 — 다른 종합소득을 모르면 계산하지 않고 되묻는다. 모델도 부르지 않는다.
        // 0 으로 놓고 계산한 뒤 "[전제] 0원으로 뒀습니다"를 붙이는 방식은 위험하다.
        // 모델이 그 한 줄을 빼먹으면 "추가 납부하실 세액 없음"만 남는다.
        if (!known.hasOtherIncome()) {
            state.awaitOtherIncome(conversationId);
            String text = ("%s\n\n종합과세 대상입니다. 다만 세액을 계산하려면 이자·배당 외 종합소득금액"
                    + "(근로·사업·연금·기타 소득금액)이 필요한데 아직 알려주시지 않았습니다. "
                    + "해당 소득이 없으시면 \"없어요\"라고 답해 주시면 그대로 계산합니다. "
                    + "총급여나 매출이 아니라 원천징수영수증·신고서의 '소득금액' 칸의 값을 알려 주세요.")
                    .formatted(String.join(" / ", cls.steps()));
            notices.add("다른 종합소득이 미확인이라 세액을 계산하지 않았습니다.");
            return new Facts(text, null, overview, null, true);
        }

        TaxCalculation calc = calcTools.calculateTax(cls.financialIncome(),
                income.domesticDividend(), known.otherIncomeOrZero(), known.deduction(),
                income.withheldTax() + known.prepaidTax());
        calls.add(new AnswerDto.ToolCall("calculateTax",
                "financialIncome=%d, grossUpEligible=%d, otherIncome=%d(%s), deduction=%d, prepaid=%d".formatted(
                        cls.financialIncome(), income.domesticDividend(), known.otherIncomeOrZero(),
                        known.otherIncomeKind() == null ? "종류 미상" : known.otherIncomeKind() + "소득금액",
                        known.deduction(), income.withheldTax() + known.prepaidTax()),
                String.valueOf(calc.decidedTax())));
        sb.append(String.join("\n", calc.steps()));
        if (known.otherIncomeKind() != null) {
            sb.append("\n(그 밖의 종합소득 ").append(won(known.otherIncomeOrZero())).append("원은 고객이 말한 ")
              .append(known.otherIncomeKind()).append("소득금액이다)");
        }
        if (income.excludedWithheldTax() > 0) {
            sb.append("\n(비과세·분리과세 상품의 원천징수 ").append(won(income.excludedWithheldTax()))
              .append("원은 그 소득과 함께 과세가 끝난 것이라 기납부세액에 넣지 않았다)");
        }

        if (income.foreignDividend() > 0) {
            // 못 하는 것을 못 한다고 말하되, 놓치면 안 되는 것은 짚어준다.
            sb.append("\n[안내] 해외주식 매도차익이 있으면 양도소득세가 별도 신고 대상입니다. "
                    + "분류과세라 위 종합소득 계산에는 포함되지 않았습니다(소득세법 §14①). "
                    + "해외 배당에 외국에서 낸 세금이 있으면 외국납부세액공제(소득세법 §57) 대상이 될 수 있으나 반영하지 않았습니다.");
            notices.add("해외배당의 외국납부세액공제(소득세법 §57)는 반영하지 않았습니다.");
        }
        return new Facts(sb.toString(), calc, overview, null, false);
    }

    /** ACTION — 접수하지 않는다. 미리보기를 걸고 명시적 확인을 기다린다. */
    private Facts proposeAction(String conversationId, ToolContext ctx, String question, StringBuilder sb,
                                AccountIncomeTools.IncomeOverview overview, List<String> notices) {
        TaxSaverProduct product = TaxSaverProduct.TAX_FREE_SAVINGS;
        String duplicate = findPending(ctx, product);
        if (duplicate != null) {
            notices.add("같은 상품의 자격 확인 신청(접수번호 " + duplicate + ")이 이미 처리 대기 중이라 새로 접수하지 않습니다.");
            sb.append("[사실] ").append(label(product)).append(" 자격 확인 신청(접수번호 ").append(duplicate)
              .append(")이 이미 PENDING 상태다. 새로 접수하지 말고 처리 대기 중임을 안내하라.\n");
            return new Facts(sb.toString(), null, overview, null, false);
        }
        state.proposeAction(conversationId, product, "상담 중 절세상품 문의: " + abbreviate(question));
        sb.append("[사실] 절세상품 안내만 하라. 자격 확인 신청은 아직 접수되지 않았다. ")
          .append("고객이 원하면 \"신청합니다\"라고 답해야 접수된다고 안내하고, 가입을 권유하지 마라. ")
          .append("이 접수는 실습용 내부 기록이며 실제 가입 시스템과 연결돼 있지 않다.\n");
        notices.add("신청은 접수되지 않았습니다. \"신청합니다\"라고 답하시면 실습용 내부 기록으로 접수됩니다. 실제 상품 가입 시스템과는 연결되어 있지 않습니다.");
        var preview = new AnswerDto.PendingAction(product.name(), label(product), true,
                "\"신청합니다\" 또는 \"네\"라고 답하면 접수, \"아니요\"라고 답하면 취소됩니다. 다른 질문을 하시면 미리보기는 만료됩니다.");
        return new Facts(sb.toString(), null, overview, preview, false);
    }

    private static final Pattern DUE_DATE = Pattern.compile("(기한|언제까지|며칠\\s*남|얼마나\\s*남|마감)");

    // ------------------------------------------------------------------ 검색

    /** 검색을 직접 수행한다. 어드바이저에 맡기면 그쪽 템플릿이 프롬프트를 통째로 바꾼다. */
    private Search search(String question) {
        try {
            List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(props.ragTopK())
                    .similarityThreshold(props.ragSimilarityThreshold())
                    // 귀속연도 필터. 다른 해의 문서 세트가 섞여 들어와도 올해 것만 쓴다.
                    .filterExpression(DocumentMeta.YEAR + " == " + props.taxYear())
                    .build());
            List<Document> docs = found == null ? List.of() : found.stream()
                    .filter(d -> DocumentMeta.from(d.getMetadata()).effectiveYear() == props.taxYear())
                    .toList();
            return new Search(docs, false);
        } catch (RuntimeException e) {
            log.warn("근거 검색 실패: {}", e.toString());
            return new Search(List.of(), true);
        }
    }

    private record Search(List<Document> docs, boolean failed) {
        static Search none() {
            return new Search(List.of(), false);
        }
    }

    private static List<String> articlesOf(List<Document> docs) {
        return docs.stream().map(d -> DocumentMeta.from(d.getMetadata()).article()).toList();
    }

    private List<AnswerDto.Citation> toCitations(List<Document> docs, String answer) {
        Set<String> cited = citedArticles(answer);
        return docs.stream().map(d -> {
            DocumentMeta m = DocumentMeta.from(d.getMetadata());
            String a = normalize(m.article());
            boolean used = !a.isBlank() && cited.stream().anyMatch(c -> a.contains(c) || c.contains(a));
            return new AnswerDto.Citation(m.doc(), m.article(), d.getScore() == null ? 0.0 : d.getScore(),
                    m.kind(), m.effectiveYear(), m.lawVersion(), m.verifiedOn(),
                    excerpt(d.getText()), used);
        }).toList();
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    // ------------------------------------------------------------------ 프롬프트

    /**
     * 프롬프트 조립. <b>도구 결과를 맨 앞에 두고 "확정된 사실"이라고 못 박는다.</b>
     *
     * <p>처음에는 "이 숫자만 사용하세요" 정도로 적었는데, 모델이 그 블록을 참고자료로 보고
     * 자기가 다시 계산하거나 "정보가 부족하다"고 답했다(BUILD-LOG Step 4).
     * 계산 결과는 참고자료가 아니라 <b>이미 확정된 답</b>이라는 것을 문장 수준에서 못 박아야 한다.
     */
    private String compose(String facts, List<Document> docs, boolean calculated) {  // NOSONAR
        StringBuilder sb = new StringBuilder();
        // 근거를 먼저, 고객 확정 사실을 나중에 넣는다.
        // 뒤집으면 모델이 법령 일반론으로 답을 채우고 고객 금액을 흘린다.
        if (!docs.isEmpty()) {
            sb.append("[근거 — 이 안에서만 세법 내용을 인용하세요. 법령(LAW)과 자체 안내(INTERNAL)를 구분하세요]\n");
            for (Document d : docs) {
                DocumentMeta m = DocumentMeta.from(d.getMetadata());
                sb.append("· 출처: ").append(m.article())
                  .append(" (").append(m.effectiveYear()).append(" 귀속 · ").append(m.kind());
                if (!m.lawVersion().isBlank()) {
                    sb.append(" · ").append(m.lawVersion());
                }
                sb.append(")\n").append(d.getText()).append("\n\n");
            }
        }
        if (!facts.isBlank()) {
            sb.append("""
                    ===== 확정 사실 (시스템이 이미 계산을 마친 결과) =====
                    아래 숫자는 고객 계좌 조회와 세법 계산으로 검증된 값입니다.
                    다시 계산하지 말고 그대로 인용해 설명만 하세요.
                    "정보가 부족하다", "계산할 수 없다"고 답하지 마세요. 필요한 값은 아래에 다 있습니다.
                    금액을 말할 때는 아래에 적힌 숫자를 그대로 옮겨 적으세요.
                    [단서]·[주의]·[사실] 로 표시된 줄은 반드시 답변에 반영하세요. 빼면 틀린 안내가 됩니다.
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
        if (calculated) {
            // 모델이 답변 말미에 반드시 붙이도록 프롬프트에도 싣는다.
            // DTO 에만 담으면 채팅 화면에서 문구가 빠질 수 있다.
            sb.append("[답변 말미에 반드시 그대로 덧붙일 문구]\n").append(calcDisclaimer).append("\n\n");
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

    // ------------------------------------------------------------------ 문구

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
                    다만 이미 알고 계신 소득금액(총급여·매출이 아닌 '소득금액')을 알려주시면,
                    금융소득과 합산해 종합과세 판정과 예상 세액을 안내해 드리겠습니다.""";
            case NO_ACCESS -> """
                    조회 권한이 없어 해당 계좌는 확인해 드릴 수 없습니다.
                    마이데이터 연동을 추가하시거나, "타사 이자 500만원" 처럼 금액을 알려주시면 합산해 계산해 드리겠습니다.""";
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
                + "(비과세·분리과세 제외분 %,d원) / 과세대상분 원천징수 %,d원")
                .formatted(s.year(), s.interest(), s.domesticDividend(), s.foreignDividend(),
                        s.taxableTotal(), s.excludedTotal(), s.withheldTax());

        // 기관·출처별로도 적는다. "저희가 확인한 금액"의 범위를 모델이 말할 수 있어야 한다.
        String bySource = s.items().stream()
                .map(i -> "%s(%s) %s %,d원".formatted(i.institution(), sourceLabel(i.source()), i.productName(), i.amount()))
                .collect(Collectors.joining(" · "));
        String out = bySource.isEmpty() ? head : head + "\n확인된 내역: " + bySource;

        // 제외분은 총액이 아니라 상품명까지 싣는다. 도구가 items 로 목록을 주는데 여기서 버리면
        // "제 ISA 는 왜 빠졌나요?"에 댈 근거가 없어진다.
        String excluded = s.items().stream()
                .filter(i -> i.treatment() != TaxTreatment.TAXABLE)
                .map(i -> "[%s] 에서 고객이 실제로 받은 소득 %,d원 — %s"
                        .formatted(i.productName(), i.amount(), label(i.treatment())))
                .collect(Collectors.joining(" · "));
        return excluded.isEmpty() ? out
                : out + "\n고객님이 보유한 상품 중 종합과세 판정에서 빠진 것: " + excluded;
    }

    private static String sourceLabel(String source) {
        return switch (source) {
            case "OWN_ACCOUNT" -> "자사";
            case "LINKED" -> "마이데이터 연동";
            default -> "고객 제공";
        };
    }

    /** "세금이 없습니다"와 "9%로 과세가 끝났습니다"는 다른 말이다. 뭉치면 답변 문구가 틀린다. */
    private static String label(TaxTreatment t) {
        return switch (t) {
            case TAX_FREE -> "비과세 — 세금 자체가 없음";
            case SEPARATE -> "분리과세 — 별도 세율로 과세 종결, 종합소득에 합산 안 함";
            case TAXABLE -> "종합과세 대상";
        };
    }

    static String label(TaxSaverProduct p) {
        return switch (p) {
            case TAX_FREE_SAVINGS -> "비과세종합저축";
            case ISA_LOW_INCOME -> "ISA 서민형";
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

    /** 되묻기가 걸려 있으면 어떤 답에든 한 줄 덧붙인다. 다른 질문을 해도 진행 중인 일은 잊지 않는다. */
    private static String withReminder(String answer, ConversationState.Facts known) {
        if (known == null || !known.awaitingOtherIncome() || answer == null || answer.contains(REMINDER)) {
            return answer;
        }
        return answer + "\n\n" + REMINDER;
    }

    private static final String REMINDER =
            "※ 앞서 여쭤본 이자·배당 외 종합소득금액(근로·사업·연금·기타)을 알려주시면 세액 계산을 이어가겠습니다. 없으시면 \"없어요\"라고 답해 주세요.";

    private AnswerDto plain(Route route, String answer, List<String> notices, long t0) {
        return new AnswerDto(route, answer, List.of(), List.of(), null, null, List.copyOf(notices), null,
                new AnswerDto.Tokens(0, 0), ms(t0), disclaimer, props.ruleLabel());
    }

    /** 총급여를 근로소득금액으로 오인하는 것은 실무에서 가장 흔한 오류다. 계산 대신 되묻는다. */
    static final String GROSS_PAY_REASK = """
            종합소득에 합산되는 것은 총급여(연봉)가 아니라 근로소득공제를 뺀 '근로소득금액'입니다. \
            총급여로 계산하면 세액이 실제보다 크게 나옵니다. \
            근로소득 원천징수영수증의 '근로소득금액' 항목을 알려주시면 그 값으로 계산해 드리겠습니다. \
            본 안내는 실습용 예상치이며 최종 판단은 세무 전문가와 상담하시기 바랍니다.""";

    /** 매출(수입금액)은 사업소득금액이 아니다. 필요경비를 빼기 전 값이라 그대로 쓰면 과대 산출된다. */
    static final String SALES_REASK = """
            종합소득에 합산되는 것은 매출(수입금액)이 아니라 필요경비를 뺀 '사업소득금액'입니다. \
            매출로 계산하면 세액이 실제보다 크게 나옵니다. \
            종합소득세 신고서(또는 소득금액증명)의 '사업소득금액'을 알려주시면 그 값으로 계산해 드리겠습니다. \
            본 안내는 실습용 예상치이며 최종 판단은 세무 전문가와 상담하시기 바랍니다.""";

    static final String RULES_UNAVAILABLE =
            "죄송합니다. 적용할 세법 규칙의 버전이 확인되지 않아 지금은 판정과 계산을 안내해 드릴 수 없습니다. "
            + "운영자에게 문의해 주세요.";

    static final String LOOKUP_FAILED =
            "죄송합니다. 지금 계좌 금융소득을 조회하지 못해 종합과세 여부를 판정할 수 없습니다. "
            + "조회가 안 된 것이지 금융소득이 없다는 뜻이 아닙니다. 잠시 후 다시 시도해 주세요.";

    static final String NO_EVIDENCE =
            "말씀하신 내용에 대한 근거를 저희 안내 문서에서 찾지 못해 답변을 보류합니다. "
            + "국세상담센터(126) 또는 국세청 홈택스 안내를 확인해 주세요. "
            + "본 안내는 실습용이며 최종 판단은 세무 전문가와 상담하시기 바랍니다.";

    private static final Pattern CITED = Pattern.compile("\\[출처:\\s*([^\\](]+)");
    private static final Pattern LAW_ARTICLE = Pattern.compile(
            "(소득세법|조세특례제한법|조특법|국세기본법|세무사법)\\s*§[0-9의①-⑳\\s가-힣호]*");

    /**
     * 답변이 붙인 <code>[출처: …]</code> 가 실제 회수 결과(또는 도구 전개가 밝힌 조문)에 있는지 확인한다.
     *
     * <p>프롬프트로 "근거에 없으면 지어내지 말라"고 요구했지만 지켜지지 않는 경우가 있었다.
     * 회수되지 않은 조문을 출처로 달면 형식상 정상 답변과 구분되지 않으므로,
     * 사람이 법령을 직접 찾아보기 전에는 틀린 줄 모른다. 그래서 코드가 대조한다.
     *
     * <p><b>허용 목록이 비어 있는데 출처를 달았으면 그것도 지어낸 것이다.</b> 예전에는 검색 결과가
     * 없으면 검사를 건너뛰었고, 그 틈으로 도구 경로의 답변이 아무 조문이나 붙일 수 있었다.
     * 도구 전개 안의 조문(§62 · §17③ 등)은 코드가 적은 것이므로 허용한다.
     *
     * @param retrievedArticles 검색이 돌려준 조문
     * @param toolArticles      도구 계산 전개에 코드가 적어 둔 조문
     */
    static String groundCitations(String answer, List<String> retrievedArticles, List<String> toolArticles) {
        if (answer == null) {
            return null;
        }
        List<String> allowed = new ArrayList<>();
        retrievedArticles.stream().map(TaxDeskService::normalize).filter(a -> !a.isBlank()).forEach(allowed::add);
        toolArticles.stream().map(TaxDeskService::normalize).filter(a -> !a.isBlank()).forEach(allowed::add);

        Matcher m = CITED.matcher(answer);
        while (m.find()) {
            String cited = normalize(m.group(1));
            if (cited.isBlank()) {
                continue;
            }
            boolean ok = allowed.stream().anyMatch(a -> a.contains(cited) || cited.contains(a));
            if (!ok) {
                log.warn("회수되지 않은 조문을 인용했다: {} (허용: {})", m.group(1).trim(), allowed);
                return UNGROUNDED;
            }
        }
        return answer;
    }

    /** 이전 서명. 도구 조문 없이 검색 결과만으로 대조한다. */
    static String groundCitations(String answer, List<String> retrievedArticles) {
        return groundCitations(answer, retrievedArticles, List.of());
    }

    /** 도구 전개 텍스트에 코드가 적어 둔 조문들. 모델이 이를 출처로 달면 지어낸 것이 아니다. */
    static List<String> lawArticlesIn(String facts) {
        if (facts == null || facts.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LAW_ARTICLE.matcher(facts);
        while (m.find()) {
            out.add(m.group().trim().replace("조특법", "조세특례제한법"));
        }
        return List.copyOf(out);
    }

    private static Set<String> citedArticles(String answer) {
        Set<String> out = new LinkedHashSet<>();
        if (answer == null) {
            return out;
        }
        Matcher m = CITED.matcher(answer);
        while (m.find()) {
            String c = normalize(m.group(1));
            if (!c.isBlank()) {
                out.add(c);
            }
        }
        return out;
    }

    /** 조문 표기가 "§17③"·"§ 17 ③"·"§17③ 각 호" 처럼 흔들리므로 공백과 귀속연도를 걷어낸다. */
    private static String normalize(String article) {
        return article == null ? "" : article.replaceAll("\\(.*?\\)", "").replaceAll("[\\s·]", "")
                .replace("조특법", "조세특례제한법");
    }

    private static final String UNGROUNDED =
            "확인되지 않습니다. 국세상담센터(126)로 문의해 주세요. "
            + "본 안내는 실습용 예상치이며 최종 판단은 세무 전문가와 상담하시기 바랍니다.";

    private static String won(long v) {
        return String.format("%,d", v);
    }

    private static String abbreviate(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 80 ? one : one.substring(0, 80) + "…";
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /**
     * @param holdWithoutModel 모델을 부르지 않고 {@code text} 를 그대로 답으로 내보낸다 (되묻기)
     */
    private record Facts(String text, TaxCalculation calculation, AccountIncomeTools.IncomeOverview overview,
                         AnswerDto.PendingAction pendingAction, boolean holdWithoutModel) {
        static Facts none() {
            return new Facts("", null, null, null, false);
        }
    }
}
