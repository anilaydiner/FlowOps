package com.flowops.application.identity;

import com.flowops.application.identity.port.PasswordHasher;
import com.flowops.application.notification.EmailComposer;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidCredentialsException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.Role;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.port.RoleRepository;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.identity.query.UserFilter;
import com.flowops.domain.organization.Department;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final IdentityReconciliationSignals reconciliation;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final RoleRepository roles;
    private final PasswordHasher passwordHasher;
    private final EmailComposer emailComposer;
    private final OutboxService outbox;
    private final AuthorizationEvaluator authz;

    public UserService(UserRepository users,
                       DepartmentRepository departments,
                       RoleRepository roles,
                       PasswordHasher passwordHasher,
                       EmailComposer emailComposer,
                       OutboxService outbox,
                       AuthorizationEvaluator authz, IdentityReconciliationSignals reconciliation) {
        this.reconciliation=reconciliation;
        this.users = users;
        this.departments = departments;
        this.roles = roles;
        this.passwordHasher = passwordHasher;
        this.emailComposer = emailComposer;
        this.outbox = outbox;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public User authenticate(String username, String password) {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));
        if (!user.isActive()) {
            throw new InvalidCredentialsException("User account is disabled");
        }
        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }
        return user;
    }

    @Transactional
    public User create(String username, String displayName, String email,
                       String departmentCode, List<String> roleCodes, String password,
                       CallerIdentity caller) {
        return create(username,displayName,email,departmentCode,roleCodes,password,null,caller);
    }

    @Transactional
    public User create(String username, String displayName, String email, String departmentCode,
                       List<String> roleCodes, String password, Boolean active, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.USERS_CREATE);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (users.existsByUsername(username)) {
            throw new ConflictException("Username already exists: " + username);
        }
        Set<Role> roleSet = resolveRoles(roleCodes);
        User user = User.newUser(
                username.strip(),
                displayName,
                blankToNull(email),
                resolveDepartment(departmentCode),
                roleSet,
                passwordHasher.hash(password));
        if(active != null) user.setActive(active);
        User saved = users.save(user);
        // ── Welcome email: deliver the initial credentials (outbox pattern) ─
        EmailComposer.ComposedEmail welcome = emailComposer.welcome(user, password);
        outbox.welcomeEmail(saved.getId(), welcome.subject(), welcome.text(),
                welcome.html(), null);
        reconciliation.userChanged(saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public User get(Long id, CallerIdentity caller) {
        User user = users.findByIdWithDepartment(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        boolean isSelf = caller.id().equals(user.getId());
        authz.require(caller, isSelf ? PermissionCodes.USERS_READ_SELF : PermissionCodes.USERS_READ);
        return user;
    }

    /**
     * Paged, filtered, sortable user listing.
     */
    @Transactional(readOnly = true)
    public PageResult<User> list(UserFilter filter, PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.USERS_LIST);
        PageResult<User> page = users.findAll(filter, query);
        page.content().forEach(u -> {
            if (u.getDepartment() != null) {
                u.getDepartment().getCode();
            }
        });
        return page;
    }

    @Transactional
    public User update(Long id, String displayName, String email,
                       String departmentCode, List<String> roleCodes, String newPassword,
                       CallerIdentity caller) {
        return update(id,displayName,email,departmentCode,roleCodes,newPassword,null,caller);
    }

    @Transactional
    public User update(Long id, String displayName, String email, String departmentCode,
                       List<String> roleCodes, String newPassword, Boolean active, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.USERS_UPDATE);
        User user = users.findByIdWithDepartment(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        var previousRoles=user.roleCodes();
        var previousDepartment=user.getDepartment()==null?null:user.getDepartment().getCode();
        boolean previousActive=user.isActive();
        if (active != null) user.setActive(active);
        if (displayName != null || email != null || departmentCode != null) {
            user.updateProfile(displayName == null ? user.getDisplayName() : displayName,
                    email == null ? user.getEmail() : blankToNull(email),
                    departmentCode == null ? user.getDepartment() : resolveDepartment(departmentCode));
        }
        if (roleCodes != null) {
            user.setRoles(resolveRoles(roleCodes));
        }
        if (newPassword != null && !newPassword.isBlank()) {
            user.changePasswordHash(passwordHasher.hash(newPassword));
        }
        User saved=users.save(user);
        var department=saved.getDepartment()==null?null:saved.getDepartment().getCode();
        if(previousActive!=saved.isActive() || !previousRoles.equals(saved.roleCodes())
                || !java.util.Objects.equals(previousDepartment,department)) reconciliation.userChanged(id);
        return saved;
    }

    @Transactional
    public void delete(Long id, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.USERS_DELETE);
        if (id.equals(caller.id())) {
            throw new ConflictException("Cannot delete your own account");
        }
        User user = users.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        reconciliation.userChanged(id);
        users.delete(user);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Set<Role> resolveRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            // Default to MEMBER
            return Set.of(roles.findByCode("MEMBER")
                    .orElseThrow(() -> new IllegalStateException("Default role MEMBER not found")));
        }
        Set<Role> result = new LinkedHashSet<>();
        for (String code : roleCodes) {
            if (code == null || code.isBlank()) continue;
            Role role = roles.findByCode(code.strip().toUpperCase())
                    .orElseThrow(() -> new NotFoundException("Role not found: " + code));
            result.add(role);
        }
        if (result.isEmpty()) {
            result.add(roles.findByCode("MEMBER")
                    .orElseThrow(() -> new IllegalStateException("Default role MEMBER not found")));
        }
        return result;
    }

    private Department resolveDepartment(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return departments.findByCode(code.strip().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Department not found: " + code));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

}
