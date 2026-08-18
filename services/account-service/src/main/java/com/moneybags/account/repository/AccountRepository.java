package com.moneybags.account.repository;

import com.moneybags.account.entity.Account;
import com.moneybags.account.entity.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * SELECT ... FOR UPDATE. Every money path (holds, consume, release, projections)
     * uses this rather than optimistic locking.
     *
     * <p>The reason is not performance, it is error semantics: transaction-service's
     * {@code FeignErrorConfiguration} maps ANY 409 from the hold endpoint to
     * INSUFFICIENT_FUNDS. Under optimistic locking a concurrent hold on a hot account
     * would raise a version conflict, surface as 409, and be reported to a teller as
     * "insufficient funds" on an account that is perfectly well funded. Pessimistic
     * locking keeps 409 meaning exactly one thing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountId = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") String accountId);

    Optional<Account> findByAccountNumber(String accountNumber);

    @Query("""
            SELECT a FROM Account a
            WHERE (:cifNo IS NULL OR a.cifNo = :cifNo)
              AND (:branchCode IS NULL OR a.branchCode = :branchCode)
              AND (:productCode IS NULL OR a.productCode = :productCode)
              AND (:status IS NULL OR a.status = :status)
            """)
    Page<Account> search(@Param("cifNo") String cifNo,
                         @Param("branchCode") String branchCode,
                         @Param("productCode") String productCode,
                         @Param("status") AccountStatus status,
                         Pageable pageable);

    long countByCifNoAndStatus(String cifNo, AccountStatus status);

    List<Account> findByStatusAndProductCodeIn(AccountStatus status, Collection<String> productCodes);

    List<Account> findByCifNoAndStatusAndProductCodeIn(
            String cifNo, AccountStatus status, Collection<String> productCodes);
}
