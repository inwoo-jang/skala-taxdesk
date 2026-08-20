package com.inwoo.taxdesk.repository;

import com.inwoo.taxdesk.domain.ApplicationStatus;
import com.inwoo.taxdesk.domain.ProductApplication;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 4 — 절세상품 자격 확인 신청 저장소.
 *
 * <p><b>소유자 조건 없는 조회 메서드를 추가하지 말 것.</b> 편의 메서드 하나가
 * 나중에 감사 로그에서 사고로 발견된다. 아래 {@code findByStatus} 가 유일한 예외이고,
 * 그것도 ADMIN 경로 전용이다.
 */
public interface ProductApplicationRepository extends JpaRepository<ProductApplication, String> {

    /** 고객이 자기 신청 내역을 볼 때. 소유자 조건이 쿼리에 박혀 있다. */
    List<ProductApplication> findByOwnerIdOrderByRequestedAtDesc(String ownerId);

    /** 도구가 상태를 바꾸려 해도 소유자가 아니면 애초에 못 찾는다. */
    Optional<ProductApplication> findByIdAndOwnerId(String id, String ownerId);

    /**
     * 승인 대기 목록. <b>이 인터페이스에서 유일하게 소유자 조건이 없는 메서드다.</b>
     * ADMIN 경로에서만 쓴다 — {@code SecurityConfig} 가 {@code /admin/**} 을 막는다.
     */
    List<ProductApplication> findByStatus(ApplicationStatus status);
}
