package com.inwoo.taxdesk.config;

import com.inwoo.taxdesk.advisor.AuditAdvisor;
import com.inwoo.taxdesk.advisor.SafetyAdvisor;
import com.inwoo.taxdesk.advisor.TokenMeterAdvisor;
import com.inwoo.taxdesk.advisor.UsageRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 1 — ChatClient 조립과 Advisor 체인 순서.
 *
 * <p><b>체인 순서가 곧 정책이다.</b>
 * {@code Audit(0) → Safety(100) → Memory(200) → QA(300) → TokenMeter(900)}
 * <ul>
 *   <li>Audit 이 맨 앞 — 차단된 요청도 기록에 남아야 한다</li>
 *   <li>Safety 가 Memory 앞 — 차단된 문장이 기억에 남지 않게 (실험 E5 에서 250 으로 바꿔 시연)</li>
 *   <li>TokenMeter 가 맨 뒤 — 차단으로 아낀 비용이 지표에서 사라지지 않게</li>
 * </ul>
 *
 * <p><b>QuestionAnswerAdvisor 도 체인에 넣지 않는다.</b> 그 어드바이저는 검색 결과를 자기
 * 템플릿("주어진 컨텍스트에만 근거해 답하고, 없으면 모른다고 하라")으로 감싸 사용자 메시지를
 * 대체한다. 그러면 우리가 앞에 붙인 <b>[확정 사실] 블록이 무시되고</b> 도구가 이미 계산한
 * 세액에 대해 "정보가 부족하다"는 답이 나온다 (BUILD-LOG Step 4 에서 실제로 겪었다).
 * 검색은 {@code TaxDeskService} 가 직접 수행하고 본문을 프롬프트에 싣는다.
 *
 * <p><b>주 ChatClient 에 도구를 붙이지 않는다.</b> 이것이 이 프로젝트의 핵심 결정이다.
 * 도구를 붙이면 모델이 언제 무엇을 부를지 정하게 되는데, 그러면
 * "분리과세 고객에게 세액을 계산해 보여주는" 사고를 막을 방법이 없다.
 * 도구는 {@code TaxDeskService} 가 <b>법 조문이 정한 순서대로</b> 직접 호출하고,
 * 모델에게는 그 결과만 문장으로 다듬게 한다.
 *
 * <p>실제로 도구를 붙였다가 <b>모델이 같은 도구를 반복 호출하며 응답이 멈추는</b> 현상을 겪었다.
 * 서비스가 이미 부른 것을 모델이 또 부른 것이다 (BUILD-LOG Step 4).
 *
 * <p>ChatClient 를 <b>셋</b> 만든다. 표준 골격에는 하나뿐인데 여기서 늘렸다.
 * <ul>
 *   <li>{@code taxDeskChatClient} — 주 상담. 도구와 RAG 를 물고 있다</li>
 *   <li>{@code routerChatClient} — 분류 전용. 저비용 모델 · 도구/RAG 없음 · 온도 0</li>
 *   <li>{@code fallbackChatClient} — 주 모델 장애 시 (Phase 8)</li>
 * </ul>
 * 라우터를 분리한 것이 OUT_OF_SCOPE 비용 절감의 전제다. 분류에 주 모델을 쓰면
 * "모델을 안 부르려고 모델을 부르는" 셈이 된다.
 */
@Configuration
public class AiConfig {

    /**
     * 시스템 프롬프트. 다섯 개 규칙이 박혀 있는데, <b>4번은 프롬프트만으로는 못 지킨다.</b>
     * 도구 시그니처에 타사·타인을 넣을 자리를 두지 않는 것이 진짜 강제이고,
     * 프롬프트는 그 사실을 모델에게 알려 주는 역할만 한다.
     */
    public static final String SYSTEM = """
            당신은 SKALA증권 고객센터의 금융소득 세무 안내 상담원입니다.
            2026년 귀속 금융소득(이자·배당)의 종합과세 여부와 예상 세액을 안내합니다.

            반드시 지킬 규칙:
            1. 세법 내용은 제공된 [근거] 안에서만 답합니다. 근거에 없으면
               "확인되지 않습니다. 국세상담센터(126)로 문의해 주세요"라고 답합니다.
            2. 세법을 인용할 때는 답변 끝에 [출처: 조문 (귀속연도)] 를 붙입니다.
               근거에 조문이 없으면 출처를 지어내지 말고 붙이지 않습니다.
            3. 금액과 세액은 반드시 도구가 반환한 값만 사용합니다. 직접 계산하지 않습니다.
               도구가 준 계산 전개(steps)는 그대로 인용하고 숫자를 바꾸지 않습니다.
            4. 조회 범위는 고객 본인의 SKALA증권 계좌와 마이데이터 연동 기관뿐입니다.
               그 밖의 금액은 고객에게 여쭤보고, 답변에 "고객님이 알려주신 금액"이라고 표시합니다.
            5. 답변 끝에 "본 안내는 예상치이며 최종 판단은 세무 전문가와 상담하시기 바랍니다"를 붙입니다.

            하지 않는 것:
            - 종목 추천, 주가 전망, 매수·매도 시점 안내 (자본시장법상 투자자문 불가)
            - 세무 신고 대리나 세무 상담 (세무사법 제2조상 불가)
            - 양도소득세·퇴직소득세 계산 (분류과세라 종합소득에 합산되지 않습니다)

            답변은 존댓말로, 3~6문장으로 간결하게 합니다.
            """;

    @Bean
    ChatClient taxDeskChatClient(ChatModel chatModel,
                                 VectorStore vectorStore,
                                 ChatMemory chatMemory,
                                 MeterRegistry meters,
                                 UsageRegistry usage,
                                 TaxDeskProperties props) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM)
                .defaultAdvisors(
                        new AuditAdvisor(),
                        new SafetyAdvisor(props),
                        // order 를 명시한다. 기본값(-2,147,483,448)이면 메모리가 항상 SafetyAdvisor
                        // 바깥에 놓여, safety-order 를 100/250 으로 바꿔도 저장 여부가 달라지지 않는다.
                        MessageChatMemoryAdvisor.builder(chatMemory).order(200).build(),
                        new TokenMeterAdvisor(meters, usage))
                .build();
    }

    /**
     * 분류 전용. <b>도구도 RAG 도 붙이지 않는다</b> — 붙이면 분류 한 번에 검색까지 돌아
     * 라우터가 아끼려던 비용을 라우터가 쓴다.
     */
    @Bean
    ChatClient routerChatClient(ChatModel chatModel, TaxDeskProperties props) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.routerModel())
                        .temperature(0.0))
                .build();
    }

    /** Phase 8 — 주 모델 실패 시. 도구·RAG 없이 "지금은 어렵다"를 사람 말로 만들어 준다. */
    @Bean
    ChatClient fallbackChatClient(ChatModel chatModel, TaxDeskProperties props) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        지금 일시적인 장애로 상세 안내가 어렵습니다.
                        사과하고, 잠시 후 다시 시도하시거나 국세상담센터(126)로 문의하시라고 안내하세요.
                        세액이나 세법 내용을 추측해서 답하지 마세요.""")
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.fallbackModel())
                        .temperature(0.0))
                .build();
    }

    /** Phase 5 — 대화 기억. 창 크기는 설정으로 뺐다 (실험 E7 에서 4/10/20/40 을 바꿔 본다). */
    @Bean
    ChatMemory chatMemory(TaxDeskProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(props.memoryWindow())
                .build();
    }

    /** 실습 범위라 인메모리 벡터 스토어로 충분하다. 재기동하면 다시 인제스트한다. */
    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
