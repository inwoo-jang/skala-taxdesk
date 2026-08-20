package com.inwoo.taxdesk.advisor;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Phase 8 — 토큰·지연 계측. 체인의 <b>맨 뒤(order 900)</b>다.
 *
 * <p>맨 뒤여야 실제로 모델을 부른 호출만 계측된다. 앞에 두면 {@link SafetyAdvisor} 가
 * 차단한 요청까지 "모델 호출"로 세어져 <b>라우터·차단이 아낀 비용이 지표에서 사라진다.</b>
 *
 * <p>기록을 두 군데에 남긴다.
 * <ul>
 *   <li><b>Micrometer</b> — {@code route} 태그만. 사용자를 태그로 달면 카디널리티가 폭발한다</li>
 *   <li><b>{@link UsageRegistry}</b> — 사용자별 누적. 비용을 물릴 단위가 사용자이기 때문이다</li>
 * </ul>
 */
public class TokenMeterAdvisor implements CallAdvisor {

    public static final int ORDER = 900;

    private final MeterRegistry meters;
    private final UsageRegistry usage;

    public TokenMeterAdvisor(MeterRegistry meters, UsageRegistry usage) {
        this.meters = meters;
        this.usage = usage;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        long t0 = System.nanoTime();
        ChatClientResponse res = chain.nextCall(req);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        String user = String.valueOf(req.context().getOrDefault("userId", "-"));
        String route = String.valueOf(req.context().getOrDefault("route", "-"));
        boolean blocked = Boolean.TRUE.equals(res.context().get("blocked"));

        long prompt = 0;
        long completion = 0;
        if (res.chatResponse() != null && res.chatResponse().getMetadata() != null) {
            Usage u = res.chatResponse().getMetadata().getUsage();
            if (u != null) {
                prompt = u.getPromptTokens() == null ? 0 : u.getPromptTokens();
                completion = u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
            }
        }

        meters.counter("taxdesk.tokens", "type", "prompt", "route", route).increment(prompt);
        meters.counter("taxdesk.tokens", "type", "completion", "route", route).increment(completion);
        meters.counter("taxdesk.route", "type", route).increment();
        meters.timer("taxdesk.latency", "route", route)
                .record(java.time.Duration.ofMillis(ms));

        usage.record(user, route, prompt, completion, ms, !blocked && prompt > 0);
        return res;
    }

    @Override
    public String getName() {
        return "tokenMeter";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
