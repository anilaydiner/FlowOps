package com.flowops.persistence;

import com.flowops.persistence.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long>, JpaSpecificationExecutor<UserEntity> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    @Query("select u from User u left join fetch u.department order by u.id")
    List<UserEntity> findAllWithDepartment();

    @Query("select u from User u left join fetch u.department where u.id = :id")
    Optional<UserEntity> findByIdWithDepartment(@Param("id") Long id);

    /** Active users carrying a role, excluding one user (SoD-aware eligibility probe). */
    @Query("select count(u) from User u join u.roles r where r.code = :role and u.active = true and u.id <> :excludeId")
    long countActiveByRoleCode(@Param("role") String role, @Param("excludeId") Long excludeId);

    /** Active users carrying a given role code (for SLA escalation). */
    @Query("select u from User u join u.roles r where r.code = :role and u.active = true")
    List<UserEntity> findActiveByRoleCode(@Param("role") String role);

    /** Coarse permission check: does the user hold a permission with the given code (via any role)? */
    @Query(value = "SELECT COUNT(*) > 0 FROM role_permissions rp " +
            "JOIN roles r ON rp.role_id = r.id " +
            "JOIN permissions p ON rp.permission_id = p.id " +
            "JOIN user_roles ur ON ur.role_id = r.id " +
            "WHERE ur.user_id = :userId AND p.code = :code", nativeQuery = true)
    boolean hasPermission(@Param("userId") Long userId, @Param("code") String code);
}
