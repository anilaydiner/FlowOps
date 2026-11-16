package com.flowops;

import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.port.RoleRepository;
import com.flowops.domain.User;
import com.flowops.domain.port.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.flowops.infra.worker.JobWorker;
import com.flowops.infra.worker.OutboxDispatcher;
import com.flowops.infra.worker.SlaScanner;

import static org.assertj.core.api.Assertions.assertThat;

class FlowOpsApplicationTests extends AbstractIntegrationTest {
    // Keep this cached context from polling the database used by manual worker tests.
    @MockitoBean JobWorker backgroundJobs;
    @MockitoBean OutboxDispatcher backgroundOutbox;
    @MockitoBean SlaScanner backgroundSla;


    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private com.flowops.domain.port.RequestTypeRepository requestTypeRepository;

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
