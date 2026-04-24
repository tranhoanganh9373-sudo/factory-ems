package com.ems.auth.service;
import com.ems.auth.dto.*;
import com.ems.core.dto.PageDTO;

public interface UserService {
    UserDTO create(CreateUserReq req);
    UserDTO update(Long id, UpdateUserReq req);
    void delete(Long id);
    UserDTO getById(Long id);
    PageDTO<UserDTO> list(int page, int size, String keyword);
    void assignRoles(Long id, AssignRolesReq req);
    void changePassword(Long id, String oldPassword, String newPassword);
    void resetPassword(Long id, String newPassword);
}
