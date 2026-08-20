package com.inwoo.taxdesk.repository;

import com.inwoo.taxdesk.domain.AccountIncome;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountIncomeRepository extends JpaRepository<AccountIncome, String> {

    /**
     * 권한이 쿼리 안에 있다.
     *
     * <p>전부 가져와서 자바 코드로 소유자를 거르는 방식과는 다르다.
     * 그 방식은 "거르기를 깜빡한 경로" 하나만 생겨도 뚫린다. 여기서는 그런 경로를 만들 수가 없다.
     *
     * <p>{@code ownerId} 를 받지 않는 조회 메서드는 이 인터페이스에 <b>추가하지 않는다.</b>
     * 편의 메서드 하나가 나중에 감사 로그에서 사고로 발견된다.
     */
    List<AccountIncome> findByOwnerIdAndYear(String ownerId, int year);
}
