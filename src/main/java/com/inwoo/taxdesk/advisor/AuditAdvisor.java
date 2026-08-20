package com.inwoo.taxdesk.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * Phase 7 — 대화 감사. 체인의 <b>맨 앞(order 0)</b>에 둔다.
 *
 * <p>맨 앞이어야 하는 이유: 뒤에 두면 {@link SafetyAdvisor} 가 차단한 요청이
 * 감사 로그에 안 남는다. <b>차단된 시도야말로 남겨야 할 기록</b>이다.
 *
 * <p>{@code ToolAuditAspect}(도구 감사)와 두 층을 이룬다. 여기는 "무엇을 묻고 답했나",
 * 저기는 "실제로 어떤 데이터에 닿았나". 사고 조사에는 둘 다 필요하다.
 */
public class AuditAdvisor implements CallAdvisor {

    public static final int ORDER = 0;

    private static final Logger audit = LoggerFactory.getLogger("AI_AUDIT");

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        String user = String.valueOf(req.context().getOrDefault("userId", "-"));
        String conv = String.valueOf(req.context().getOrDefault("conversationId", "-"));
        String question = Masker.mask(req.prompt().getUserMessage().getText());
        long t0 = System.nanoTime();

        audit.info("user={} conv={} IN  q=\"{}\"", user, conv, abbreviate(question));
        try {
            ChatClientResponse res = chain.nextCall(req);
            String answer = res.chatResponse() == null ? ""
                    : Masker.mask(res.chatResponse().getResult().getOutput().getText());
            audit.info("user={} conv={} OUT a=\"{}\" elapsedMs={}",
                    user, conv, abbreviate(answer), ms(t0));
            return res;
        } catch (RuntimeException e) {
            audit.warn("user={} conv={} ERR {} elapsedMs={}",
                    user, conv, e.getClass().getSimpleName(), ms(t0));
            throw e;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Override
    public String getName() {
        return "audit";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
