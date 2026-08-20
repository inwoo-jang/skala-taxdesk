package com.inwoo.taxdesk.advisor;

import com.inwoo.taxdesk.config.TaxDeskProperties;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Phase 7 — 입력 차단과 마스킹.
 *
 * <p>order 가 {@code taxdesk.safety-order} 로 바뀐다. <b>100 이면 메모리 앞(정상),
 * 250 이면 뒤</b> — 차단된 문장이 기억에 남는 것을 눈으로 보여주는 실습용 값이다(실험 E5).
 *
 * <p>세 가지를 한다.
 * <ol>
 *   <li><b>길이 상한</b> — 긴 입력을 그대로 모델에 넘기면 토큰 비용 공격이 된다</li>
 *   <li><b>인젝션 패턴 차단</b> — 모델을 부르지 <b>않고</b> 즉시 응답한다. 차단은 비용이 0 이어야 한다</li>
 *   <li><b>마스킹</b> — 주민번호·계좌번호가 프롬프트에 실려 나가지 않게 한다</li>
 * </ol>
 *
 * <p>패턴 매칭은 마지막 방어선이 아니다. 진짜 방어는 도구 시그니처에 남의 ID 를 넣을 자리를
 * 두지 않는 것이고, 이건 <b>싸게 걸러 내는 층</b>이다.
 */
public class SafetyAdvisor implements CallAdvisor {

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("(이전|위의|앞의|모든)\\s*(지시|명령|규칙|프롬프트).{0,10}(무시|잊)"),
            Pattern.compile("(시스템|system)\\s*(프롬프트|prompt).{0,10}(출력|보여|알려|공개)"),
            Pattern.compile("ignore\\s+(all\\s+)?(previous|above)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(너는|당신은)\\s*이제.{0,20}(세무사|변호사|관리자|admin)"),
            Pattern.compile("(개발자|관리자|admin)\\s*(모드|권한).{0,10}(활성|전환|진입)"),
            Pattern.compile("(전체|모든)\\s*(고객|사용자|계좌).{0,10}(조회|목록|보여)"));

    private final TaxDeskProperties props;

    public SafetyAdvisor(TaxDeskProperties props) {
        this.props = props;
    }

    /**
     * 검사 대상은 <b>고객이 실제로 입력한 문장</b>이다. 서비스가 도구 결과와 출처를 붙여 만든
     * 최종 프롬프트가 아니다. 후자를 검사하면 근거를 많이 찾아올수록 차단되는
     * 어처구니없는 동작이 된다 — 실제로 그렇게 만들었다가 잡았다(BUILD-LOG Step 4).
     */
    static final String RAW_QUESTION = "rawQuestion";

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        Object raw = req.context().get(RAW_QUESTION);
        String text = raw != null ? raw.toString() : req.prompt().getUserMessage().getText();

        if (text != null && text.length() > props.maxInputLength()) {
            return blocked(req, "질문이 너무 깁니다. %d자 이내로 줄여 주세요."
                    .formatted(props.maxInputLength()));
        }
        if (text != null && INJECTION.stream().anyMatch(p -> p.matcher(text).find())) {
            // 모델을 부르지 않는다. 차단의 비용은 0 이어야 한다.
            return blocked(req, """
                    죄송합니다. 요청하신 내용은 처리해 드릴 수 없습니다.
                    저는 SKALA증권 금융소득 세무 안내만 도와드릴 수 있습니다.""");
        }

        String prompt = req.prompt().getUserMessage().getText();
        String masked = Masker.mask(prompt);
        if (masked != null && !masked.equals(prompt)) {
            // 원문 대신 마스킹본이 모델과 메모리로 간다. 되돌릴 수 없게 여기서 바꾼다.
            ChatClientRequest safe = req.mutate()
                    .prompt(new Prompt(new UserMessage(masked), req.prompt().getOptions()))
                    .build();
            return chain.nextCall(safe);
        }
        return chain.nextCall(req);
    }

    /** 모델을 거치지 않고 만든 응답. 체인의 나머지도 타지 않는다. */
    private ChatClientResponse blocked(ChatClientRequest req, String message) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(message)))))
                .context(req.context())
                .context("blocked", true)
                .build();
    }

    @Override
    public String getName() {
        return "safety";
    }

    @Override
    public int getOrder() {
        return props.safetyOrder();
    }
}
