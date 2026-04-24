package com.ems.orgtree.service.impl;

import com.ems.audit.annotation.Audited;
import com.ems.core.exception.BusinessException;
import com.ems.core.exception.NotFoundException;
import com.ems.core.constant.ErrorCode;
import com.ems.orgtree.dto.*;
import com.ems.orgtree.entity.OrgNode;
import com.ems.orgtree.repository.OrgNodeClosureRepository;
import com.ems.orgtree.repository.OrgNodeRepository;
import com.ems.orgtree.service.OrgNodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrgNodeServiceImpl implements OrgNodeService {

    private final OrgNodeRepository nodes;
    private final OrgNodeClosureRepository closure;

    public OrgNodeServiceImpl(OrgNodeRepository n, OrgNodeClosureRepository c) {
        this.nodes = n; this.closure = c;
    }

    @Override
    @Transactional
    @Audited(action = "CREATE", resourceType = "ORG_NODE", resourceIdExpr = "#result.id()")
    public OrgNodeDTO create(CreateOrgNodeReq req) {
        if (nodes.existsByCode(req.code()))
            throw new BusinessException(ErrorCode.CONFLICT, "节点编码已存在: " + req.code());

        if (req.parentId() != null) {
            nodes.findById(req.parentId())
                .orElseThrow(() -> new NotFoundException("OrgNode", req.parentId()));
        }

        OrgNode n = new OrgNode();
        n.setParentId(req.parentId());
        n.setName(req.name());
        n.setCode(req.code());
        n.setNodeType(req.nodeType());
        n.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        nodes.save(n);

        if (req.parentId() == null) closure.insertForRoot(n.getId());
        else                         closure.insertForNewLeaf(n.getId(), req.parentId());

        return toDTO(n, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public OrgNodeDTO getById(Long id) {
        OrgNode n = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
        return toDTO(n, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgNodeDTO> getTree(Long rootId) {
        List<OrgNode> all = nodes.findAllByOrderBySortOrderAscIdAsc();
        Set<Long> scope;
        if (rootId == null) {
            scope = all.stream().map(OrgNode::getId).collect(Collectors.toSet());
        } else {
            scope = new HashSet<>(closure.findDescendantIds(rootId));
            if (scope.isEmpty()) throw new NotFoundException("OrgNode", rootId);
        }
        Map<Long, List<OrgNode>> byParent = new HashMap<>();
        for (OrgNode n : all) {
            if (!scope.contains(n.getId())) continue;
            byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
        }
        List<OrgNode> roots;
        if (rootId == null) roots = byParent.getOrDefault(null, List.of());
        else roots = all.stream().filter(x -> x.getId().equals(rootId)).toList();

        return roots.stream().map(r -> buildSubtree(r, byParent)).toList();
    }

    private OrgNodeDTO buildSubtree(OrgNode n, Map<Long, List<OrgNode>> byParent) {
        List<OrgNodeDTO> kids = byParent.getOrDefault(n.getId(), List.of())
            .stream().map(k -> buildSubtree(k, byParent)).toList();
        return toDTO(n, kids);
    }

    @Override
    @Transactional
    @Audited(action = "UPDATE", resourceType = "ORG_NODE", resourceIdExpr = "#id")
    public OrgNodeDTO update(Long id, UpdateOrgNodeReq req) {
        OrgNode n = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
        n.setName(req.name());
        n.setNodeType(req.nodeType());
        if (req.sortOrder() != null) n.setSortOrder(req.sortOrder());
        nodes.save(n);
        return toDTO(n, List.of());
    }

    @Override
    @Transactional
    @Audited(action = "MOVE", resourceType = "ORG_NODE", resourceIdExpr = "#id")
    public void move(Long id, MoveOrgNodeReq req) {
        OrgNode node = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
        Long newParent = req.newParentId();

        if (Objects.equals(node.getParentId(), newParent)) return;  // 无变化

        if (newParent != null) {
            nodes.findById(newParent).orElseThrow(() -> new NotFoundException("OrgNode", newParent));
            if (closure.isDescendant(id, newParent)) {
                throw new BusinessException(ErrorCode.BIZ_GENERIC, "不能把节点移到自己的后代下");
            }
        }

        // 1. 删除旧祖先对子树的闭包
        closure.deleteAncestorsOfSubtree(id);
        // 2. 插入新祖先对子树的闭包（如果 newParent 为 null，节点变成根，不需要插）
        if (newParent != null) closure.insertAncestorsForSubtree(id, newParent);
        // 3. 更新 parent_id
        node.setParentId(newParent);
        nodes.save(node);
    }

    @Override
    @Transactional
    @Audited(action = "DELETE", resourceType = "ORG_NODE", resourceIdExpr = "#id")
    public void delete(Long id) {
        OrgNode n = nodes.findById(id).orElseThrow(() -> new NotFoundException("OrgNode", id));
        List<Long> descendants = closure.findDescendantIds(id);
        if (descendants.size() > 1) {
            throw new BusinessException(ErrorCode.BIZ_GENERIC, "节点下仍有子节点，请先删除子节点");
        }
        nodes.delete(n);
    }

    @Override
    public List<Long> findDescendantIds(Long id) { return closure.findDescendantIds(id); }

    private OrgNodeDTO toDTO(OrgNode n, List<OrgNodeDTO> children) {
        return new OrgNodeDTO(n.getId(), n.getParentId(), n.getName(), n.getCode(),
            n.getNodeType(), n.getSortOrder(), n.getCreatedAt(), children);
    }
}
