package com.inwoo.taxdesk.web;

import com.inwoo.taxdesk.advisor.UsageRegistry;
import com.inwoo.taxdesk.domain.ApplicationStatus;
import com.inwoo.taxdesk.domain.ProductApplication;
import com.inwoo.taxdesk.rag.IngestService;
import com.inwoo.taxdesk.repository.ProductApplicationRepository;
import java.security.Principal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2·7 — 운영자 API. 인제스트와 승인 게이트.
 *
 * <p>절세상품 자격 확인의 <b>결정</b>이 여기 있는 것이 핵심이다. 도구는 PENDING 까지만 만들고,
 * APPROVED / REJECTED 로 넘기는 권한은 ADMIN 역할에만 있다.
 *
 * <p>승인과 거절이 <b>대칭</b>이어야 한다. 거절 경로가 없으면 자격 미달 고객이 PENDING 에
 * 영원히 남고, 그건 게이트가 아니라 병목이다.
 *
 * <p>ADMIN 의 행위는 여기서 직접 감사 로그로 남긴다 — 모델을 거치지 않는 경로라
 * {@code AuditAdvisor} 체인에 걸리지 않기 때문이다.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger audit = LoggerFactory.getLogger("AI_AUDIT");

    private final IngestService ingestService;
    private final ProductApplicationRepository applications;
    private final UsageRegistry usage;

    public AdminController(IngestService ingestService,
                           ProductApplicationRepository applications,
                           UsageRegistry usage) {
        this.ingestService = ingestService;
        this.applications = applications;
        this.usage = usage;
    }

    /** Phase 2 — 문서 색인. 응답의 {@code missingArticleCount} 가 0 이어야 검증 통과다. */
    @PostMapping("/ingest")
    public IngestService.IngestResult ingest(Principal principal) {
        audit.info("admin={} action=ingest", principal.getName());
        return ingestService.ingest();
    }

    @GetMapping("/applications")
    public List<ProductApplication> list(
            @RequestParam(defaultValue = "PENDING") ApplicationStatus status) {
        return applications.findByStatus(status);
    }

    @PostMapping("/applications/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id,
                                     @RequestBody(required = false) Decision decision,
                                     Principal principal) {
        return decide(id, ApplicationStatus.APPROVED,
                decision == null ? "자격 확인 완료" : decision.note(), principal);
    }

    @PostMapping("/applications/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable String id,
                                    @RequestBody Decision decision,
                                    Principal principal) {
        if (decision == null || decision.note() == null || decision.note().isBlank()) {
            // 거절에는 사유가 반드시 붙는다. 사유 없는 거절은 응대가 아니다.
            throw new IllegalArgumentException("거절 사유(note)는 필수입니다.");
        }
        return decide(id, ApplicationStatus.REJECTED, decision.note(), principal);
    }

    private ResponseEntity<?> decide(String id, ApplicationStatus status, String note,
                                     Principal principal) {
        ProductApplication app = applications.findById(id)
                .orElseThrow(() -> new com.inwoo.taxdesk.common.web.NotFoundException(
                        "신청 건을 찾을 수 없습니다: " + id));
        if (app.getStatus() != ApplicationStatus.PENDING) {
            // 되돌릴 수 없는 행동이므로 재결정은 막는다.
            return ResponseEntity.status(409)
                    .body("이미 " + app.getStatus() + " 로 결정된 건입니다.");
        }
        app.decide(status, note);
        applications.save(app);
        audit.info("admin={} action={} applicationId={} owner={} note=\"{}\"",
                principal.getName(), status, id, app.getOwnerId(), note);
        return ResponseEntity.ok(app);
    }

    /**
     * Phase 8 — <b>사용자별</b> 토큰 사용량. 요금을 물릴 단위가 사용자이기 때문이다.
     *
     * <p>Micrometer 지표에는 사용자를 태그로 달지 않는다 — 카디널리티가 사용자 수만큼 늘어난다.
     * 그래서 {@link UsageRegistry} 에 따로 모으고 이 경로로만 노출한다.
     */
    @GetMapping("/usage")
    public List<UsageRegistry.UserUsage> usage() {
        return usage.snapshot();
    }

    @GetMapping("/usage/{userId}")
    public UsageRegistry.UserUsage usageOf(@PathVariable String userId) {
        return usage.of(userId);
    }

    public record Decision(String note) {
    }
}
