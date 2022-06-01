/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.rule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.flow.TbRuleChainInputNode;
import org.thingsboard.rule.engine.flow.TbRuleChainInputNodeConfiguration;
import org.thingsboard.rule.engine.flow.TbRuleChainOutputNode;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.rule.DefaultRuleChainCreateRequest;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleChainOutputLabelsUsage;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.rule.RuleChainUpdateResult;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.data.rule.RuleNodeUpdateResult;
import org.thingsboard.server.dao.relation.RelationService;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.AbstractTbEntityService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@TbCoreComponent
@Slf4j
public class DefaultTbRuleChainService extends AbstractTbEntityService implements TbRuleChainService {

    private final RuleChainService ruleChainService;
    private final RelationService relationService;

    @Override
    public Set<String> getRuleChainOutputLabels(TenantId tenantId, RuleChainId ruleChainId) {
        RuleChainMetaData metaData = ruleChainService.loadRuleChainMetaData(tenantId, ruleChainId);
        Set<String> outputLabels = new TreeSet<>();
        for (RuleNode ruleNode : metaData.getNodes()) {
            if (isOutputRuleNode(ruleNode)) {
                outputLabels.add(ruleNode.getName());
            }
        }
        return outputLabels;
    }

    @Override
    public List<RuleChainOutputLabelsUsage> getOutputLabelUsage(TenantId tenantId, RuleChainId ruleChainId) {
        List<RuleNode> ruleNodes = ruleChainService.findRuleNodesByTenantIdAndType(tenantId, TbRuleChainInputNode.class.getName(), ruleChainId.getId().toString());
        Map<RuleChainId, String> ruleChainNamesCache = new HashMap<>();
        // Additional filter, "just in case" the structure of the JSON configuration will change.
        var filteredRuleNodes = ruleNodes.stream().filter(node -> {
            try {
                TbRuleChainInputNodeConfiguration configuration = JacksonUtil.treeToValue(node.getConfiguration(), TbRuleChainInputNodeConfiguration.class);
                return ruleChainId.getId().toString().equals(configuration.getRuleChainId());
            } catch (Exception e) {
                log.warn("[{}][{}] Failed to decode rule node configuration", tenantId, ruleChainId, e);
                return false;
            }
        }).collect(Collectors.toList());


        return filteredRuleNodes.stream()
                .map(ruleNode -> {
                    RuleChainOutputLabelsUsage usage = new RuleChainOutputLabelsUsage();
                    usage.setRuleNodeId(ruleNode.getId());
                    usage.setRuleNodeName(ruleNode.getName());
                    usage.setRuleChainId(ruleNode.getRuleChainId());
                    List<EntityRelation> relations = ruleChainService.getRuleNodeRelations(tenantId, ruleNode.getId());
                    if (relations != null && !relations.isEmpty()) {
                        usage.setLabels(relations.stream().map(EntityRelation::getType).collect(Collectors.toSet()));
                    }
                    return usage;
                })
                .filter(usage -> usage.getLabels() != null)
                .peek(usage -> {
                    String ruleChainName = ruleChainNamesCache.computeIfAbsent(usage.getRuleChainId(),
                            id -> ruleChainService.findRuleChainById(tenantId, id).getName());
                    usage.setRuleChainName(ruleChainName);
                })
                .sorted(Comparator
                        .comparing(RuleChainOutputLabelsUsage::getRuleChainName)
                        .thenComparing(RuleChainOutputLabelsUsage::getRuleNodeName))
                .collect(Collectors.toList());
    }

    @Override
    public List<RuleChain> updateRelatedRuleChains(TenantId tenantId, RuleChainId ruleChainId, RuleChainUpdateResult result) {
        Set<RuleChainId> ruleChainIds = new HashSet<>();
        log.debug("[{}][{}] Going to update links in related rule chains", tenantId, ruleChainId);
        if (result.getUpdatedRuleNodes() == null || result.getUpdatedRuleNodes().isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> oldLabels = new HashSet<>();
        Set<String> newLabels = new HashSet<>();
        Set<String> confusedLabels = new HashSet<>();
        Map<String, String> updatedLabels = new HashMap<>();
        for (RuleNodeUpdateResult update : result.getUpdatedRuleNodes()) {
            var oldNode = update.getOldRuleNode();
            var newNode = update.getNewRuleNode();
            if (isOutputRuleNode(newNode)) {
                try {
                    oldLabels.add(oldNode.getName());
                    newLabels.add(newNode.getName());
                    if (!oldNode.getName().equals(newNode.getName())) {
                        String oldLabel = oldNode.getName();
                        String newLabel = newNode.getName();
                        if (updatedLabels.containsKey(oldLabel) && !updatedLabels.get(oldLabel).equals(newLabel)) {
                            confusedLabels.add(oldLabel);
                            log.warn("[{}][{}] Can't automatically rename the label from [{}] to [{}] due to conflict [{}]", tenantId, ruleChainId, oldLabel, newLabel, updatedLabels.get(oldLabel));
                        } else {
                            updatedLabels.put(oldLabel, newLabel);
                        }

                    }
                } catch (Exception e) {
                    log.warn("[{}][{}][{}] Failed to decode rule node configuration", tenantId, ruleChainId, newNode.getId(), e);
                }
            }
        }
        // Remove all output labels that are renamed to two or more different labels, since we don't which new label to use;
        confusedLabels.forEach(updatedLabels::remove);
        // Remove all output labels that are renamed but still present in the rule chain;
        newLabels.forEach(updatedLabels::remove);
        if (!oldLabels.equals(newLabels)) {
            ruleChainIds.addAll(updateRelatedRuleChains(tenantId, ruleChainId, updatedLabels));
        }
        return ruleChainIds.stream().map(id -> ruleChainService.findRuleChainById(tenantId, id)).collect(Collectors.toList());
    }

    @Override
    public RuleChain save(RuleChain ruleChain, SecurityUser user) throws ThingsboardException {
        TenantId tenantId = ruleChain.getTenantId();
        ActionType actionType = ruleChain.getId() == null ? ActionType.ADDED : ActionType.UPDATED;
        try {
            RuleChain savedRuleChain = checkNotNull(ruleChainService.saveRuleChain(ruleChain));

            if (RuleChainType.CORE.equals(savedRuleChain.getType())) {
                tbClusterService.broadcastEntityStateChangeEvent(tenantId, savedRuleChain.getId(),
                        actionType.equals(ActionType.ADDED) ? ComponentLifecycleEvent.CREATED : ComponentLifecycleEvent.UPDATED);
            }
            boolean isSendMsg = RuleChainType.EDGE.equals(savedRuleChain.getType()) && actionType.equals(ActionType.UPDATED);
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, savedRuleChain.getId(),
                    savedRuleChain, user, actionType, isSendMsg, null);
            return savedRuleChain;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    ruleChain, user, actionType, false, e);
            throw handleException(e);
        }
    }

    @Override
    public void delete(RuleChain ruleChain, SecurityUser user) throws ThingsboardException {
        TenantId tenantId = ruleChain.getTenantId();
        RuleChainId ruleChainId = ruleChain.getId();
        try {
            List<RuleNode> referencingRuleNodes = ruleChainService.getReferencingRuleChainNodes(tenantId, ruleChainId);

            Set<RuleChainId> referencingRuleChainIds = referencingRuleNodes.stream().map(RuleNode::getRuleChainId).collect(Collectors.toSet());

            List<EdgeId> relatedEdgeIds = null;
            if (RuleChainType.EDGE.equals(ruleChain.getType())) {
                relatedEdgeIds = findRelatedEdgeIds(tenantId, ruleChainId);
            }

            ruleChainService.deleteRuleChainById(tenantId, ruleChainId);

            referencingRuleChainIds.remove(ruleChain.getId());

            if (RuleChainType.CORE.equals(ruleChain.getType())) {
                referencingRuleChainIds.forEach(referencingRuleChainId ->
                        tbClusterService.broadcastEntityStateChangeEvent(tenantId, referencingRuleChainId, ComponentLifecycleEvent.UPDATED));

                tbClusterService.broadcastEntityStateChangeEvent(tenantId, ruleChain.getId(), ComponentLifecycleEvent.DELETED);
            }

            notificationEntityService.notifyDeleteRuleChain(tenantId, ruleChain, relatedEdgeIds, user);
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    null, user, ActionType.DELETED, false, e, ruleChainId.toString());
            throw handleException(e);
        }
    }

    @Override
    public RuleChain saveDefaultByName(TenantId tenantId, DefaultRuleChainCreateRequest request, SecurityUser user) throws ThingsboardException {
        try {
            RuleChain savedRuleChain = installScripts.createDefaultRuleChain(tenantId, request.getName());
            tbClusterService.broadcastEntityStateChangeEvent(tenantId, savedRuleChain.getId(), ComponentLifecycleEvent.CREATED);
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, savedRuleChain.getId(),
                    savedRuleChain, user, ActionType.ADDED, false, null);
            return savedRuleChain;
        } catch (Exception e) {
            RuleChain ruleChain = new RuleChain();
            ruleChain.setName(request.getName());
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    ruleChain, user, ActionType.ADDED, false, e);
            throw handleException(e);
        }
    }

    @Override
    public RuleChain setRootRuleChain(TenantId tenantId, RuleChain ruleChain, SecurityUser user) throws ThingsboardException {
        RuleChain previousRootRuleChain = ruleChainService.getRootTenantRuleChain(tenantId);
        RuleChainId previousRootRuleChainId = previousRootRuleChain.getId();
        RuleChainId ruleChainId = ruleChain.getId();
        try {
            if (ruleChainService.setRootRuleChain(tenantId, ruleChainId)) {
                if (previousRootRuleChain != null) {
                    previousRootRuleChain = ruleChainService.findRuleChainById(tenantId, previousRootRuleChainId);

                    tbClusterService.broadcastEntityStateChangeEvent(tenantId, previousRootRuleChainId,
                            ComponentLifecycleEvent.UPDATED);
                    notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, previousRootRuleChainId,
                            previousRootRuleChain, user, ActionType.UPDATED, false, null);
                }
                ruleChain = ruleChainService.findRuleChainById(tenantId, ruleChainId);

                tbClusterService.broadcastEntityStateChangeEvent(tenantId, ruleChainId,
                        ComponentLifecycleEvent.UPDATED);
                notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, ruleChainId,
                        ruleChain, user, ActionType.UPDATED, false, null);
            }
            return ruleChain;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    ruleChain, user, ActionType.UPDATED, false, e, ruleChainId.toString());
            throw handleException(e);
        }
    }

    @Override
    public RuleChainMetaData saveRuleChainMetaData(TenantId tenantId, RuleChain ruleChain, RuleChainMetaData ruleChainMetaData,
                                                   boolean updateRelated, SecurityUser user) throws ThingsboardException {
        RuleChainId ruleChainId = ruleChain.getId();
        RuleChainId ruleChainMetaDataId = ruleChainMetaData.getRuleChainId();
        try {
            RuleChainUpdateResult result = ruleChainService.saveRuleChainMetaData(tenantId, ruleChainMetaData);
            checkNotNull(result.isSuccess() ? true : null);

            List<RuleChain> updatedRuleChains;
            if (updateRelated && result.isSuccess()) {
                updatedRuleChains = tbRuleChainService.updateRelatedRuleChains(tenantId, ruleChainMetaDataId, result);
            } else {
                updatedRuleChains = Collections.emptyList();
            }

            RuleChainMetaData savedRuleChainMetaData = checkNotNull(ruleChainService.loadRuleChainMetaData(tenantId, ruleChainMetaDataId));

            if (RuleChainType.CORE.equals(ruleChain.getType())) {
                tbClusterService.broadcastEntityStateChangeEvent(tenantId, ruleChainId, ComponentLifecycleEvent.UPDATED);
                updatedRuleChains.forEach(updatedRuleChain -> {
                    tbClusterService.broadcastEntityStateChangeEvent(tenantId, updatedRuleChain.getId(), ComponentLifecycleEvent.UPDATED);
                });
            }

            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, ruleChainId,
                    ruleChain, user, ActionType.UPDATED, false, null, ruleChainMetaData);

            if (RuleChainType.EDGE.equals(ruleChain.getType())) {
                notificationEntityService.notifySendMsgToEdgeService(tenantId, ruleChain.getId(), EdgeEventActionType.UPDATED);
            }

            for (RuleChain updatedRuleChain : updatedRuleChains) {
                if (RuleChainType.EDGE.equals(ruleChain.getType())) {
                    notificationEntityService.notifySendMsgToEdgeService(tenantId, updatedRuleChain.getId(), EdgeEventActionType.UPDATED);
                } else {
                    RuleChainMetaData updatedRuleChainMetaData = checkNotNull(ruleChainService.loadRuleChainMetaData(tenantId, updatedRuleChain.getId()));
                    notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, updatedRuleChain.getId(),
                            updatedRuleChain, user, ActionType.UPDATED, false, null, updatedRuleChainMetaData);
                }
            }
            return savedRuleChainMetaData;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    null, user, ActionType.ADDED, false, e, ruleChainMetaData);
            throw handleException(e);
        }
    }

    @Override
    public RuleChain assignRuleChainToEdge(TenantId tenantId, RuleChain ruleChain, Edge edge, SecurityUser user) throws ThingsboardException {
        RuleChainId ruleChainId = ruleChain.getId();
        try {
            RuleChain savedRuleChain = checkNotNull(ruleChainService.assignRuleChainToEdge(tenantId, ruleChainId, edge.getId()));
            notificationEntityService.notifyAssignOrUnassignEntityToEdge(tenantId, ruleChainId,
                    null, edge.getId(),
                    savedRuleChain, ActionType.ASSIGNED_TO_EDGE,
                    user, ruleChainId.toString(), edge.getId().toString(), edge.getName());
            return savedRuleChain;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    null, user, ActionType.ASSIGNED_TO_EDGE, false, e, ruleChainId.toString(), edge.getId().toString());
            throw handleException(e);
        }
    }

    @Override
    public RuleChain unassignRuleChainToEdge(TenantId tenantId, RuleChain ruleChain, Edge edge, SecurityUser user) throws ThingsboardException {
        RuleChainId ruleChainId = ruleChain.getId();
        try {
            RuleChain savedRuleChain = checkNotNull(ruleChainService.unassignRuleChainFromEdge(tenantId, ruleChainId, edge.getId(), false));
            notificationEntityService.notifyAssignOrUnassignEntityToEdge(tenantId, ruleChainId,
                    null, edge.getId(),
                    savedRuleChain, ActionType.UNASSIGNED_FROM_EDGE,
                    user, ruleChainId.toString(), edge.getId().toString(), edge.getName());
            return savedRuleChain;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    null, user, ActionType.UNASSIGNED_FROM_EDGE, false, e, ruleChainId.toString(), edge.getId().toString());
            throw handleException(e);
        }
    }

    @Override
    public RuleChain setEdgeTemplateRootRuleChain(TenantId tenantId, RuleChain ruleChain, SecurityUser user) throws ThingsboardException {
        RuleChainId ruleChainId = ruleChain.getId();
        try {
            ruleChainService.setEdgeTemplateRootRuleChain(tenantId, ruleChainId);

            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, ruleChainId,
                    ruleChain, user, ActionType.UPDATED, false, null);
            return ruleChain;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    null, user, ActionType.UPDATED, false, e, ruleChainId.toString());
            throw handleException(e);
        }
    }

    @Override
    public RuleChain setAutoAssignToEdgeRuleChain(TenantId tenantId, RuleChain ruleChain, SecurityUser user) throws ThingsboardException {
        RuleChainId ruleChainId = ruleChain.getId();
        try {
            ruleChainService.setAutoAssignToEdgeRuleChain(tenantId, ruleChainId);

            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, ruleChainId,
                    ruleChain, user, ActionType.UPDATED, false, null);
            return ruleChain;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    null, user, ActionType.UPDATED, false, e, ruleChainId.toString());
            throw handleException(e);
        }
    }

    @Override
    public RuleChain unsetAutoAssignToEdgeRuleChain(TenantId tenantId, RuleChain ruleChain, SecurityUser user) throws ThingsboardException {
        RuleChainId ruleChainId = ruleChain.getId();
        try {
            ruleChainService.unsetAutoAssignToEdgeRuleChain(tenantId, ruleChainId);
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, ruleChainId,
                    ruleChain, user, ActionType.UPDATED, false, null);
            return ruleChain;
        } catch (Exception e) {
            notificationEntityService.notifyCreateOrUpdateOrDelete(tenantId, null, emptyId(EntityType.RULE_CHAIN),
                    null, user, ActionType.UPDATED, false, e, ruleChainId.toString());
            throw handleException(e);
        }
    }

    public Set<RuleChainId> updateRelatedRuleChains(TenantId tenantId, RuleChainId ruleChainId, Map<String, String> labelsMap) {
        Set<RuleChainId> updatedRuleChains = new HashSet<>();
        List<RuleChainOutputLabelsUsage> usageList = getOutputLabelUsage(tenantId, ruleChainId);
        for (RuleChainOutputLabelsUsage usage : usageList) {
            labelsMap.forEach((oldLabel, newLabel) -> {
                if (usage.getLabels().contains(oldLabel)) {
                    updatedRuleChains.add(usage.getRuleChainId());
                    renameOutgoingLinks(tenantId, usage.getRuleNodeId(), oldLabel, newLabel);
                }
            });
        }
        return updatedRuleChains;
    }

    private void renameOutgoingLinks(TenantId tenantId, RuleNodeId ruleNodeId, String oldLabel, String newLabel) {
        List<EntityRelation> relations = ruleChainService.getRuleNodeRelations(tenantId, ruleNodeId);
        for (EntityRelation relation : relations) {
            if (relation.getType().equals(oldLabel)) {
                relationService.deleteRelation(tenantId, relation);
                relation.setType(newLabel);
                relationService.saveRelation(tenantId, relation);
            }
        }
    }

    private boolean isOutputRuleNode(RuleNode ruleNode) {
        return isRuleNode(ruleNode, TbRuleChainOutputNode.class);
    }

    private boolean isInputRuleNode(RuleNode ruleNode) {
        return isRuleNode(ruleNode, TbRuleChainInputNode.class);
    }

    private boolean isRuleNode(RuleNode ruleNode, Class<?> clazz) {
        return ruleNode != null && ruleNode.getType().equals(clazz.getName());
    }

}
