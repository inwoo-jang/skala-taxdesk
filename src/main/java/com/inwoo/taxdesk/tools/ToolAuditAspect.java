package com.inwoo.taxdesk.tools;

import java.util.Arrays;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Phase 7 — 도구 호출 감사. <b>대화 감사({@code AuditAdvisor})와 별개의 층이다.</b>
 *
 * <p>둘 다 필요한 이유: 대화 로그는 "무엇을 물었고 무엇을 답했나"를 남기지만
 * <b>실제로 어떤 데이터에 닿았는지</b>는 안 남는다. 사고가 났을 때 확인해야 하는 것은 후자다.
 *
 * <p>인자에 금액과 계좌 관련 값이 들어가므로 {@code Masker} 를 거친 뒤 남긴다.
 */
@Aspect
@Component
public class ToolAuditAspect {

    private static final Logger audit = LoggerFactory.getLogger("AI_TOOL_AUDIT");

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getName();
        String user = userOf(pjp.getArgs());
        long t0 = System.nanoTime();
        try {
            Object result = pjp.proceed();
            audit.info("tool={} user={} args={} ok elapsedMs={}",
                    name, user, argsOf(pjp.getArgs()), ms(t0));
            return result;
        } catch (Throwable e) {
            // 실패도 남긴다. 차단된 시도가 로그에 없으면 방어했다는 증거도 없다.
            audit.warn("tool={} user={} args={} FAILED reason={} elapsedMs={}",
                    name, user, argsOf(pjp.getArgs()), e.getClass().getSimpleName(), ms(t0));
            throw e;
        }
    }

    private static String userOf(Object[] args) {
        return Arrays.stream(args)
                .filter(ToolContext.class::isInstance)
                .map(ToolContext.class::cast)
                .map(c -> String.valueOf(c.getContext().get(ToolUser.KEY)))
                .findFirst()
                .orElse("-");
    }

    private static String argsOf(Object[] args) {
        return Arrays.stream(args)
                .filter(a -> !(a instanceof ToolContext))
                .map(a -> com.inwoo.taxdesk.advisor.Masker.mask(String.valueOf(a)))
                .toList()
                .toString();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
