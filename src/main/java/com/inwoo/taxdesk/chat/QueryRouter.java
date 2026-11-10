package com.inwoo.taxdesk.chat;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Phase 3 — 질문을 {@link Route} 로 분류한다. <b>이 실습의 고유 설계 포인트다.</b>
 *
 * <p>어떤 부품을 부를지를 모델에게 매번 맡기지 않고 <b>먼저 결정한다.</b>
 * 특히 {@link Route#OUT_OF_SCOPE} 는 주 모델을 아예 호출하지 않으므로
 * 비용과 안전을 같은 지점에서 막는다.
 *
 * <p>2단 구조다.
 * <ol>
 *   <li><b>규칙 먼저.</b> 거절해야 할 것(양도세 · 투자자문 · 타인 계좌)은 패턴으로 즉시 잡는다.
 *       모델 호출이 0회라 <b>가장 싸고 가장 확실하다</b></li>
 *   <li><b>모델 분류.</b> 규칙에 안 걸린 나머지를 저비용 모델이 1회 분류한다</li>
 * </ol>
 * 순서가 반대면 안 된다. 모델을 먼저 부르면 거절 케이스에서도 토큰이 나가고,
 * 그러면 "거절은 공짜"라는 설계가 무너진다.
 *
 * <p><b>주의</b> — HYBRID 라도 RAG 근거에서 기준값(2,000만원)을 파싱해 Tool 입력으로 넘기지 않는다.
 * 기준은 설정({@code taxdesk.threshold-amount})에 있고, RAG 는 그 기준의 <b>근거 문서</b>를 제시할 뿐이다.
 * 이 분리가 무너지면 "문서를 잘못 읽으면 세금이 틀리는" 구조가 된다.
 */
@Component
public class QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    /** 분류 결과에 거절 사유까지 담는다. 거절 문구를 고르려면 "왜"가 필요하다. */
    public record Decision(Route route, Refusal refusal, String reason) {
        public static Decision of(Route r) {
            return new Decision(r, null, null);
        }

        public boolean refused() {
            return route == Route.OUT_OF_SCOPE;
        }
    }

    /** 거절의 네 갈래. 근거 법령이 각각 다르다. */
    public enum Refusal {
        /** 양도·퇴직소득 — 분류과세라 종합소득에 합산 자체가 안 된다 (소득세법 §14①) */
        CLASSIFIED_INCOME,
        /** 사업·근로·연금·기타 — 종합소득이지만 우리가 계산 못 한다. 금액을 주면 합산 (§14②) */
        OTHER_COMPREHENSIVE,
        /** 타사·타인 계좌 — 조회 권한 밖. 금액을 주면 합산 */
        NO_ACCESS,
        /** 종목·시황 — 자본시장법상 투자자문 불가 */
        INVESTMENT_ADVICE,
        /** 신고 대리 — 세무사법 §2 */
        TAX_AGENCY
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Refusal.INVESTMENT_ADVICE, Pattern.compile(
                    "(오를|내릴|떨어질|상승|하락|전망|추천|사도\\s*되|살까|팔까|팔면|팔아야|사야|매수|매도|"
                            + "손해|손실|수익률\\s*좋|어떤\\s*종목)")),
            new Rule(Refusal.CLASSIFIED_INCOME, Pattern.compile(
                    "(양도\\s*소?득?세?|주식.{0,5}팔|퇴직\\s*금?소?득?세?|퇴직금)")),
            new Rule(Refusal.NO_ACCESS, Pattern.compile(
                    "(타사|다른\\s*증권사|다른\\s*은행|배우자|남편|아내|가족).{0,10}(계좌|조회|배당|이자)"
                            + "|(전체|모든)\\s*(고객|사용자)")),
            new Rule(Refusal.TAX_AGENCY, Pattern.compile(
                    "(신고|납부).{0,6}(대신|대리)|대신.{0,4}신고")),
            new Rule(Refusal.OTHER_COMPREHENSIVE, Pattern.compile(
                    "(사업\\s*소득|부동산\\s*임대|임대\\s*소득|근로\\s*소득).{0,12}(계산|산출|얼마)")));

    private static final Pattern SIMULATION = Pattern.compile(
            "(얼마까지|더\\s*받아도|더\\s*받으면|넘지\\s*않으려|안\\s*넘으려|여유|한도.{0,4}남|미리\\s*점검)");
    /**
     * <b>상품명만으로는 ACTION 이 아니다.</b> "ISA 가입할래요"는 신청이지만
     * "제 ISA 도 금융소득에 들어가나요?"는 판정 질문이다. 상품명을 리터럴로 넣으면
     * 판정 질문까지 신청 경로로 새서 묻지도 않은 자격확인이 접수된다.
     * 행동 의도가 있을 때만 잡는다.
     */
    private static final Pattern ACTION = Pattern.compile(
            "(가입|신청|줄일\\s*방법|절세|혜택.{0,6}상품)");
    // 사이에 상품명이 끼어도 "내 것"이다 — "제 ISA 도 금융소득에" 처럼.
    private static final Pattern MINE = Pattern.compile(
            // 사이에 낱말이 셋까지 끼어도 "내 것"이다 — "제 ISA 수익도 금융소득에" (RESULTS CA-10).
            "(제|내|저의|나의|본인)\\s*(?:\\S{1,8}\\s*){0,3}(계좌|이자|배당|금융소득|세금|ISA)"
                    + "|저\\s*(종합과세|대상)"
                    // 대명사만 있는 되물음 — "그럼 저는요?" (RESULTS MT-01)
                    + "|(그럼|그러면|그렇다면)\\s*(저|나|제\\s*경우)는?\\s*요?");

    /** "며칠 남았나요" 류 — 오늘 날짜가 필요한 질문. estimateDueDate 도구가 답해야 한다. */
    private static final Pattern DUE_DATE_CALC = Pattern.compile(
            "(기한|마감).{0,12}(며칠|얼마나)\\s*남|(며칠|얼마나)\\s*남.{0,6}(기한|마감)");

    private final ChatClient router;

    public QueryRouter(@Qualifier("routerChatClient") ChatClient router) {
        this.router = router;
    }

    public Decision route(String question) {
        if (question == null || question.isBlank()) {
            return Decision.of(Route.REGULATION);
        }
        // 1단계 — 규칙. 거절은 모델 없이 끝낸다.
        // 단, "타사 배당 1,000만원 더하면요?" 처럼 고객이 금액을 알려준 문장은 거절하지 않는다.
        // 거절 문구가 "금액을 알려주시면 합산해 드립니다"라고 안내해 놓고 금액을 주면 또 거절하는 것은
        // 대안이 말뿐이라는 뜻이다 (RESULTS MT-02). 금액이 있으면 계산 경로로 보낸다.
        boolean declaresExternal = AmountParser.externalAmount(question) != null;
        for (Rule r : RULES) {
            if (r.refusal() == Refusal.NO_ACCESS && declaresExternal) {
                continue;
            }
            if (r.pattern().matcher(question).find()) {
                log.debug("규칙 분류: {} ({})", Route.OUT_OF_SCOPE, r.refusal());
                return new Decision(Route.OUT_OF_SCOPE, r.refusal(), "규칙 매칭");
            }
        }
        if (SIMULATION.matcher(question).find()) {
            return Decision.of(Route.SIMULATION);
        }
        if (ACTION.matcher(question).find()) {
            return Decision.of(Route.ACTION);
        }
        // 날짜가 들어간 질문은 RAG 로는 원리상 맞출 수 없다 (RESULTS RT-12). 도구 경로로 보낸다.
        if (DUE_DATE_CALC.matcher(question).find()) {
            return Decision.of(Route.CALCULATION);
        }
        // 계산 입력값(다른 소득·공제·기납부세액)을 말한 턴은 다시 계산해야 한다.
        // 진술문은 질문처럼 안 생겨서 REGULATION 으로 새기 쉽다.
        if (AmountParser.mentionsCalcInput(question)) {
            return Decision.of(Route.CALCULATION);
        }
        // 2단계 — 모델 1회 분류. 저비용 모델이고 도구·RAG 를 물지 않는다.
        try {
            String raw = router.prompt()
                    .system("""
                            질문을 다음 중 하나로 분류하고 그 단어만 출력하세요.
                            REGULATION  세법 규정·개념 질문 (예: 배당소득 세율이 몇 퍼센트인가요?)
                            CALCULATION 본인 금액 조회나 주어진 숫자의 세액 계산
                                        (예: 제 2026년 이자·배당 얼마인지 알려주세요)
                            HYBRID      본인 상황에 법 기준을 적용해 판정해야 하는 질문
                            SIMULATION  앞으로 더 받아도 되는 한도를 묻는 질문만 해당한다.
                                        "얼마까지 더", "더 받아도" 처럼 미래의 여유를 물어야 한다.
                                        이미 받은 금액을 묻는 질문은 CALCULATION 이다
                            ACTION      상품 가입·신청 요청
                            다른 말은 절대 붙이지 마세요.""")
                    .user(question)
                    .call()
                    .content();
            Route r = parse(raw);
            if (r != null) {
                // 본인을 가리켰는데 REGULATION 으로 분류되면 본인 데이터를 안 보고 일반론을 답한다.
                // 최소 HYBRID 로 올린다. 모델 뒤에 두는 이유는, 앞에 두면 "제 이자·배당 얼마죠?"
                // 같은 단순 조회까지 HYBRID 로 끌려와 RAG 비용을 헛되이 쓰기 때문이다.
                if (r == Route.REGULATION && MINE.matcher(question).find()) {
                    r = Route.HYBRID;
                }
                return Decision.of(r);
            }
        } catch (RuntimeException e) {
            log.warn("라우터 모델 실패, 규칙 폴백: {}", e.toString());
        }
        // 3단계 — 모델이 실패했을 때의 규칙 폴백. 서비스가 멈추면 안 된다.
        return Decision.of(MINE.matcher(question).find() ? Route.HYBRID : Route.REGULATION);
    }

    private static Route parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase().replaceAll("[^A-Z_]", "");
        for (Route r : Route.values()) {
            if (s.contains(r.name())) {
                return r;
            }
        }
        return null;
    }

    private record Rule(Refusal refusal, Pattern pattern) {
    }
}
