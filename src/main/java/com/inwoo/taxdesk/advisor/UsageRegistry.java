package com.inwoo.taxdesk.advisor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Phase 8 — <b>사용자별</b> 토큰·호출 누적. Micrometer 와 별개로 들고 있는 이유가 있다.
 *
 * <p>Micrometer 카운터에 {@code user} 를 태그로 달면 <b>카디널리티가 사용자 수만큼 늘어난다.</b>
 * 실습에서는 계정이 5개라 괜찮지만, 실제 서비스에서 그렇게 하면 시계열이 폭발한다.
 * 그래서 지표에는 태그를 달지 않고, "누가 얼마나 썼나"는 여기에 따로 모은다.
 *
 * <p>비용 추적의 단위가 사용자여야 하는 이유는 단순하다 — <b>요금을 물릴 대상이 사용자</b>이고,
 * 비정상 사용(한 계정이 토큰을 몰아 쓰는 것)도 사용자 단위로만 보인다.
 *
 * <p>인메모리라 재기동하면 사라진다. 실습 범위에서는 그걸로 충분하고,
 * 실제라면 이 자리에 사용량 테이블이나 시계열 DB 가 들어간다.
 */
@Component
public class UsageRegistry {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public void record(String userId, String route, long promptTokens, long completionTokens,
                       long latencyMs, boolean modelCalled) {
        Bucket b = buckets.computeIfAbsent(userId == null ? "-" : userId, k -> new Bucket());
        b.calls.increment();
        b.prompt.add(promptTokens);
        b.completion.add(completionTokens);
        b.latency.add(latencyMs);
        if (!modelCalled) {
            b.modelSkipped.increment();
        }
        b.routes.computeIfAbsent(route == null ? "-" : route, k -> new LongAdder()).increment();
    }

    /** 사용량 많은 순. 운영자가 가장 먼저 보고 싶은 순서다. */
    public List<UserUsage> snapshot() {
        return buckets.entrySet().stream()
                .map(e -> e.getValue().toView(e.getKey()))
                .sorted(Comparator.comparingLong(UserUsage::totalTokens).reversed())
                .toList();
    }

    public UserUsage of(String userId) {
        Bucket b = buckets.get(userId);
        return b == null ? new UserUsage(userId, 0, 0, 0, 0, 0, 0, Map.of()) : b.toView(userId);
    }

    public void reset() {
        buckets.clear();
    }

    private static final class Bucket {
        private final LongAdder calls = new LongAdder();
        private final LongAdder prompt = new LongAdder();
        private final LongAdder completion = new LongAdder();
        private final LongAdder latency = new LongAdder();
        private final LongAdder modelSkipped = new LongAdder();
        private final Map<String, LongAdder> routes = new ConcurrentHashMap<>();

        UserUsage toView(String userId) {
            long c = calls.sum();
            return new UserUsage(userId, c, prompt.sum(), completion.sum(),
                    prompt.sum() + completion.sum(),
                    c == 0 ? 0 : latency.sum() / c,
                    modelSkipped.sum(),
                    routes.entrySet().stream().collect(
                            java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                    e -> e.getValue().sum())));
        }
    }

    /**
     * @param modelSkipped 주 모델을 부르지 않고 끝낸 횟수 (OUT_OF_SCOPE · 차단 · 분리과세 종결).
     *                     <b>이 수치가 라우터가 실제로 돈을 아꼈다는 증거다.</b>
     */
    public record UserUsage(String userId,
                            long calls,
                            long promptTokens,
                            long completionTokens,
                            long totalTokens,
                            long avgLatencyMs,
                            long modelSkipped,
                            Map<String, Long> routeCounts) {
    }
}
