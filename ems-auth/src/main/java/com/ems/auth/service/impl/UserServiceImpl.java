package com.ems.auth.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.auth.dto.*;
import com.ems.auth.entity.*;
import com.ems.auth.repository.*;
import com.ems.auth.service.UserService;
import com.ems.core.dto.PageDTO;
import com.ems.core.exception.*;
import com.ems.core.constant.ErrorCode;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder encoder;

    public UserServiceImpl(UserRepository u, RoleRepository r, UserRoleRepository ur, PasswordEncoder e) {
        this.users = u; this.roles = r; this.userRoles = ur; this.encoder = e;
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "USER", resourceIdExpr = "#result.id()")
    public UserDTO create(CreateUserReq req) {
        if (users.existsByUsername(req.username()))
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        validatePassword(req.password());

        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setDisplayName(req.displayName());
        u.setEnabled(true);
        users.save(u);

        if (req.roleCodes() != null) assignRolesInternal(u.getId(), req.roleCodes());
        return toDTO(u);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "USER", resourceIdExpr = "#id")
    public UserDTO update(Long id, UpdateUserReq req) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if (req.displayName() != null) u.setDisplayName(req.displayName());
        if (req.enabled() != null) u.setEnabled(req.enabled());
        users.save(u);
        return toDTO(u);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "USER", resourceIdExpr = "#id")
    public void delete(Long id) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if ("admin".equals(u.getUsername()))
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "不能删除默认管理员");
        users.delete(u);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO getById(Long id) {
        return toDTO(users.findById(id).orElseThrow(() -> new NotFoundException("User", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageDTO<UserDTO> list(int page, int size, String keyword) {
        Pageable p = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> pg = users.findAll(p);
        List<UserDTO> items = pg.stream()
            .filter(u -> keyword == null || keyword.isBlank()
                || u.getUsername().contains(keyword)
                || (u.getDisplayName() != null && u.getDisplayName().contains(keyword)))
            .map(this::toDTO).toList();
        return PageDTO.of(items, pg.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "USER", resourceIdExpr = "#id",
             summaryExpr = "'assign roles'")
    public void assignRoles(Long id, AssignRolesReq req) {
        users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        userRoles.deleteByUserId(id);
        assignRolesInternal(id, req.roleCodes());
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "USER", resourceIdExpr = "#id",
             summaryExpr = "'change password'")
    public void changePassword(Long id, String oldPassword, String newPassword) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        if (!encoder.matches(oldPassword, u.getPasswordHash()))
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "原密码错误");
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
    }

    @Override
    @Transactional
    @Audited(action = "CONFIG_CHANGE", resourceType = "USER", resourceIdExpr = "#id",
             summaryExpr = "'admin reset password'")
    public void resetPassword(Long id, String newPassword) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
    }

    private void assignRolesInternal(Long uid, List<String> codes) {
        for (String code : codes) {
            Role r = roles.findByCode(code).orElseThrow(() ->
                new NotFoundException("Role", code));
            userRoles.save(new UserRole(uid, r.getId()));
        }
    }

    private void validatePassword(String p) {
        if (p == null || p.length() < 8)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码至少 8 位");
        if (p.matches("\\d+"))
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码不能纯数字");
    }

    private UserDTO toDTO(User u) {
        List<String> codes = userRoles.findRoleCodesByUserId(u.getId());
        return new UserDTO(u.getId(), u.getUsername(), u.getDisplayName(), u.getEnabled(),
            codes, u.getLastLoginAt(), u.getCreatedAt());
    }
}
