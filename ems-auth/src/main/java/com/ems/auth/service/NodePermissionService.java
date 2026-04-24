package com.ems.auth.service;
import com.ems.auth.dto.*;
import java.util.List;
public interface NodePermissionService {
    List<NodePermissionDTO> listByUser(Long userId);
    NodePermissionDTO assign(Long userId, AssignNodePermissionReq req);
    void revoke(Long permissionId);
}
