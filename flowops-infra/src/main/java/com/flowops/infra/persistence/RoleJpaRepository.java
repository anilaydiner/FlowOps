package com.flowops.infra.persistence;

import com.flowops.infra.persistence.entity.RoleEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface RoleJpaRepository extends JpaRepository<RoleEntity, Long>, JpaSpecificationExecutor<RoleEntity> {

    Optional<RoleEntity> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Returns true when any of the given role codes carries the named
     * permission (used for coarse permission checks like audit:read:any).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(r) > 0 FROM Role r JOIN r.permissions p " +
            "WHERE r.code IN :roleCodes AND p.code = :permCode")
    boolean anyRoleHasPermission(
            @org.springframework.data.repository.query.Param("roleCodes") java.util.Collection<String> roleCodes,
            @org.springframework.data.repository.query.Param("permCode") String permCode);
}
