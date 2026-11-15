package com.flowops.application.service;

import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;

import com.flowops.domain.query.UserFilter;
import com.flowops.domain.Department;
import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.Role;
import com.flowops.domain.port.RoleRepository;
import com.flowops.domain.User;
import com.flowops.domain.port.UserRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class UserService {

    private final UserRepository users;
    private final DepartmentRepository departments;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final EmailComposer emailComposer;
    private final OutboxService outbox;
    private final AuthorizationEvaluator authz;

    public UserService(UserRepository users,
                       DepartmentRepository departments,
                       RoleRepository roles,
                       PasswordEncoder passwordEncoder,
                       EmailComposer emailComposer,
                       OutboxService outbox,
                       AuthorizationEvaluator authz) {
        this.users = users;
        this.departments = departments;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.emailComposer = emailComposer;
        this.outbox = outbox;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public User authenticate(String username, String password) {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        if (!user.isActive()) {
            throw new BadCredentialsException("User account is disabled");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        return user;
    }

    @Transactional
    public User create(String username, String displayName, String email,
                       String departmentCode, List<String> roleCodes, String password,
                       CallerIdentity caller) {
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
                passwordEncoder.encode(password));
        User saved = users.save(user);
        // ── Welcome email: deliver the initial credentials (outbox pattern) ─
        EmailComposer.ComposedEmail welcome = emailComposer.welcome(user, password);
        outbox.welcomeEmail(saved.getId(), welcome.subject(), welcome.text(),
                welcome.html(), null);
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
        authz.require(caller, PermissionCodes.USERS_UPDATE);
        User user = users.findByIdWithDepartment(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        if (displayName != null) {
            user.updateProfile(displayName, blankToNull(email), resolveDepartment(departmentCode));
        }
        if (roleCodes != null) {
            user.setRoles(resolveRoles(roleCodes));
        }
        if (newPassword != null && !newPassword.isBlank()) {
            user.changePasswordHash(passwordEncoder.encode(newPassword));
        }
        return users.save(user);
    }

    @Transactional
    public void delete(Long id, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.USERS_DELETE);
        if (id.equals(caller.id())) {
            throw new ConflictException("Cannot delete your own account");
        }
        User user = users.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
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

    /** Internal signal for bad credentials; mapped to 401 (not 500). */
    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException(String message) {
            super(message);
        }
    }
}
