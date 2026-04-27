package com.ems.auth.repository;

import com.ems.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Search by case-insensitive substring match on username or displayName, with proper pagination.
     *  Replaces the broken in-memory filter on findAll(p) which only saw the first page. */
    @Query("SELECT u FROM User u WHERE :q IS NULL OR :q = '' "
        + "OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) "
        + "OR (u.displayName IS NOT NULL AND LOWER(u.displayName) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<User> searchByKeyword(@Param("q") String q, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.failedAttempts = 0, u.lockedUntil = null, "
        + "u.lastLoginAt = :now, u.updatedAt = :now WHERE u.id = :id")
    int markLoginSuccess(@Param("id") Long id, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.failedAttempts = :attempts, u.lockedUntil = :lockedUntil, "
        + "u.updatedAt = :now WHERE u.id = :id")
    int markLoginFailure(@Param("id") Long id, @Param("attempts") int attempts,
                         @Param("lockedUntil") OffsetDateTime lockedUntil,
                         @Param("now") OffsetDateTime now);
}
