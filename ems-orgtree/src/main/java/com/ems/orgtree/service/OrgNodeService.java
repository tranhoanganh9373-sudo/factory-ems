package com.ems.orgtree.service;

import com.ems.orgtree.dto.*;

import java.util.List;

public interface OrgNodeService {
    OrgNodeDTO create(CreateOrgNodeReq req);
    OrgNodeDTO update(Long id, UpdateOrgNodeReq req);
    void move(Long id, MoveOrgNodeReq req);
    void delete(Long id);
    OrgNodeDTO getById(Long id);
    List<OrgNodeDTO> getTree(Long rootId);                   // null => 全树
    List<Long> findDescendantIds(Long id);
}
