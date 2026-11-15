package com.flowops;

import com.flowops.infra.persistence.DepartmentJpaRepository;
import com.flowops.infra.persistence.RoleJpaRepository;
import com.flowops.domain.User;
import com.flowops.infra.persistence.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class FlowOpsApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private DepartmentJpaRepository departmentRepository;

    @Autowired
    private RoleJpaRepository roleRepository;

    @Autowired
    private com.flowops.infra.persistence.RequestTypeJpaRepository requestTypeRepository;

    @Test
    void contextLoadsAndMigrationsApply() {
        assertThat(departmentRepository.findByCode("IT")).isPresent();
        // RBAC: admin user should have ADMIN role via user_roles junction
        User admin = userRepository.findByUsername("admin").orElseThrow();
        assertThat(admin.getRoles()).extracting(com.flowops.domain.Role::getCode)
                .contains("ADMIN");
        assertThat(userRepository.findByUsername("alice")).isPresent();
        // Roles seeded
        assertThat(roleRepository.findByCode("ADMIN")).isPresent();
        assertThat(roleRepository.findByCode("MEMBER")).isPresent();
        // Iteration 3: 4 request types + new departments
        assertThat(departmentRepository.findByCode("PROCUREMENT")).isPresent();
        assertThat(departmentRepository.findByCode("COMPLIANCE")).isPresent();
        assertThat(requestTypeRepository.findByCode("EXPENSE")).isPresent();
        assertThat(requestTypeRepository.findByCode("IT_ACCESS")).isPresent();
        assertThat(requestTypeRepository.findByCode("EQUIPMENT")).isPresent();
        assertThat(requestTypeRepository.findByCode("POLICY_EXCEPTION")).isPresent();
        assertThat(requestTypeRepository.count()).isGreaterThanOrEqualTo(4);
    }
}
