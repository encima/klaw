package io.aiven.klaw.service;

import static io.aiven.klaw.model.MailType.ACL_DELETE_REQUESTED;
import static io.aiven.klaw.model.MailType.ACL_REQUESTED;
import static io.aiven.klaw.model.MailType.ACL_REQUEST_APPROVED;
import static io.aiven.klaw.model.MailType.ACL_REQUEST_DENIED;
import static io.aiven.klaw.service.KwConstants.RETRIEVE_SCHEMAS_KEY;
import static org.springframework.beans.BeanUtils.copyProperties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.aiven.klaw.config.ManageDatabase;
import io.aiven.klaw.dao.Acl;
import io.aiven.klaw.dao.AclRequests;
import io.aiven.klaw.dao.Env;
import io.aiven.klaw.dao.KwClusters;
import io.aiven.klaw.dao.Team;
import io.aiven.klaw.dao.Topic;
import io.aiven.klaw.dao.UserInfo;
import io.aiven.klaw.error.KlawException;
import io.aiven.klaw.helpers.HandleDbRequests;
import io.aiven.klaw.model.AclInfo;
import io.aiven.klaw.model.AclRequestsModel;
import io.aiven.klaw.model.KafkaClustersType;
import io.aiven.klaw.model.KafkaFlavors;
import io.aiven.klaw.model.PermissionType;
import io.aiven.klaw.model.RequestStatus;
import io.aiven.klaw.model.SyncAclUpdates;
import io.aiven.klaw.model.SyncBackAcls;
import io.aiven.klaw.model.TopicHistory;
import io.aiven.klaw.model.TopicInfo;
import io.aiven.klaw.model.TopicOverview;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AclControllerService {
  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  public static final ObjectWriter WRITER_WITH_DEFAULT_PRETTY_PRINTER =
      OBJECT_MAPPER.writerWithDefaultPrettyPrinter();
  @Autowired ManageDatabase manageDatabase;

  @Autowired private final MailUtils mailService;

  @Autowired private final ClusterApiService clusterApiService;

  @Autowired private RolesPermissionsControllerService rolesPermissionsControllerService;

  @Autowired private CommonUtilsService commonUtilsService;

  AclControllerService(ClusterApiService clusterApiService, MailUtils mailService) {
    this.clusterApiService = clusterApiService;
    this.mailService = mailService;
  }

  public String createAcl(AclRequestsModel aclReq) {
    log.info("createAcl {}", aclReq);
    String userDetails = getUserName();
    aclReq.setAclType("Create");
    aclReq.setUsername(userDetails);
    int tenantId = commonUtilsService.getTenantId(getUserName());

    aclReq.setTeamId(manageDatabase.getTeamIdFromTeamName(tenantId, aclReq.getTeamname()));
    aclReq.setRequestingteam(getMyTeamId(userDetails));

    if (commonUtilsService.isNotAuthorizedUser(
        getPrincipal(), PermissionType.REQUEST_CREATE_SUBSCRIPTIONS)) {
      return "{\"result\":\"Not Authorized\"}";
    }

    List<Topic> topics =
        manageDatabase.getHandleDbRequests().getTopics(aclReq.getTopicname(), tenantId);
    boolean topicFound = false;
    String result;

    if ("LITERAL".equals(aclReq.getAclPatternType())) {
      for (Topic topic : topics) {
        if (Objects.equals(topic.getEnvironment(), aclReq.getEnvironment())) {
          topicFound = true;
          break;
        }
      }
      result = "Failure : Topic not found on target environment.";
      if (!topicFound) return "{\"result\":\"" + result + "\"}";
    }

    if ("Consumer".equals(aclReq.getTopictype())) {
      if ("PREFIXED".equals(aclReq.getAclPatternType())) {
        result = "Failure : Please change the pattern to LITERAL for topic type.";
        return "{\"result\":\"" + result + "\"}";
      }
      if (validateTeamConsumerGroup(
          aclReq.getRequestingteam(), aclReq.getConsumergroup(), tenantId)) {
        result = "Failure : Consumer group " + aclReq.getConsumergroup() + " used by another team.";
        return "{\"result\":\"" + result + "\"}";
      }
    }

    String txnId;
    if (aclReq.getTransactionalId() != null) {
      txnId = aclReq.getTransactionalId().trim();
      if (txnId.length() > 0) aclReq.setTransactionalId(txnId);
    }

    AclRequests aclRequestsDao = new AclRequests();
    copyProperties(aclReq, aclRequestsDao);
    StringBuilder aclStr = new StringBuilder();
    String seperatorAcl = "<ACL>";
    if (aclReq.getAcl_ip() != null) {
      for (int i = 0; i < aclReq.getAcl_ip().size(); i++) {
        if (i == 0) {
          aclStr.append(aclReq.getAcl_ip().get(i));
        } else aclStr = new StringBuilder(aclStr + seperatorAcl + aclReq.getAcl_ip().get(i));
      }
      aclRequestsDao.setAcl_ip(aclStr.toString());
    }

    if (aclReq.getAcl_ssl() != null) {
      for (int i = 0; i < aclReq.getAcl_ssl().size(); i++) {
        if (i == 0) {
          aclStr.append(aclReq.getAcl_ssl().get(i));
        } else aclStr = new StringBuilder(aclStr + seperatorAcl + aclReq.getAcl_ssl().get(i));
      }
      aclRequestsDao.setAcl_ssl(aclStr.toString());
    }

    if (aclReq.getAcl_ssl() == null || aclReq.getAcl_ssl().equals("null"))
      aclRequestsDao.setAcl_ssl("User:*");

    aclRequestsDao.setTenantId(tenantId);
    String execRes =
        manageDatabase.getHandleDbRequests().requestForAcl(aclRequestsDao).get("result");

    if ("success".equals(execRes)) {
      mailService.sendMail(
          aclReq.getTopicname(),
          aclReq.getTopictype(),
          "",
          userDetails,
          manageDatabase.getHandleDbRequests(),
          ACL_REQUESTED,
          commonUtilsService.getLoginUrl());
    }
    return "{\"result\":\"" + execRes + "\"}";
  }

  public Map<String, String> updateSyncAcls(List<SyncAclUpdates> syncAclUpdates) {
    log.info("updateSyncAcls {}", syncAclUpdates);
    String userDetails = getUserName();
    Map<String, String> response = new HashMap<>();
    int tenantId = commonUtilsService.getTenantId(getUserName());

    if (commonUtilsService.isNotAuthorizedUser(getPrincipal(), PermissionType.SYNC_SUBSCRIPTIONS)) {
      response.put("result", "Not Authorized.");
      return response;
    }

    List<Acl> listTopics = new ArrayList<>();
    Acl t;

    if (syncAclUpdates != null && syncAclUpdates.size() > 0) {

      Map<String, SyncAclUpdates> stringSyncAclUpdatesHashMap = new HashMap<>();

      // remove duplicates
      for (SyncAclUpdates syncAclUpdateItem : syncAclUpdates) {
        stringSyncAclUpdatesHashMap.remove(syncAclUpdateItem.getSequence());
        stringSyncAclUpdatesHashMap.put(syncAclUpdateItem.getSequence(), syncAclUpdateItem);
      }

      for (Map.Entry<String, SyncAclUpdates> stringSyncAclUpdatesEntry :
          stringSyncAclUpdatesHashMap.entrySet()) {
        SyncAclUpdates syncAclUpdateItem = stringSyncAclUpdatesEntry.getValue();

        // tenant filtering
        if (!getEnvsFromUserId(userDetails).contains(syncAclUpdateItem.getEnvSelected())) {
          response.put("result", "Not Authorized.");
          return response;
        }

        t = new Acl();

        if (syncAclUpdateItem.getReq_no() != null)
          t.setReq_no(Integer.parseInt(syncAclUpdateItem.getReq_no()));
        t.setTopicname(syncAclUpdateItem.getTopicName());
        t.setConsumergroup(syncAclUpdateItem.getConsumerGroup());
        t.setAclip(syncAclUpdateItem.getAclIp());
        t.setAclssl(syncAclUpdateItem.getAclSsl());
        t.setTeamId(
            manageDatabase.getTeamIdFromTeamName(tenantId, syncAclUpdateItem.getTeamSelected()));
        t.setEnvironment(syncAclUpdateItem.getEnvSelected());
        t.setTopictype(syncAclUpdateItem.getAclType());
        t.setAclPatternType("LITERAL");
        t.setTenantId(tenantId);

        listTopics.add(t);
      }
    } else {
      response.put("result", "No record updated.");
      return response;
    }

    try {
      if (listTopics.size() > 0) {
        response.put("result", manageDatabase.getHandleDbRequests().addToSyncacls(listTopics));
      }
    } catch (Exception e) {
      log.error("Exception:", e);
      response.put("result", "Failure." + e);
    }
    return response;
  }

  public Map<String, List<String>> updateSyncBackAcls(SyncBackAcls syncBackAcls) {
    log.info("updateSyncBackAcls {}", syncBackAcls);
    Map<String, List<String>> resultMap = new HashMap<>();

    List<String> logArray = new ArrayList<>();
    int tenantId = commonUtilsService.getTenantId(getUserName());

    logArray.add(
        "Source Environment " + getEnvDetails(syncBackAcls.getSourceEnv(), tenantId).getName());
    logArray.add(
        "Target Environment " + getEnvDetails(syncBackAcls.getTargetEnv(), tenantId).getName());
    logArray.add("Type of Sync " + syncBackAcls.getTypeOfSync());

    if (commonUtilsService.isNotAuthorizedUser(
        getPrincipal(), PermissionType.SYNC_BACK_SUBSCRIPTIONS)) {
      List<String> notAuth = new ArrayList<>();
      notAuth.add("Not Authorized");
      resultMap.put("result", notAuth);
      return resultMap;
    }

    List<String> resultStatus = new ArrayList<>();
    resultStatus.add("success");

    resultMap.put("result", resultStatus);

    if ("SELECTED_ACLS".equals(syncBackAcls.getTypeOfSync())) {
      for (String aclId : syncBackAcls.getAclIds()) {
        Acl acl =
            manageDatabase
                .getHandleDbRequests()
                .selectSyncAclsFromReqNo(Integer.parseInt(aclId), tenantId);
        if (acl != null) {
          approveSyncBackAcls(syncBackAcls, resultMap, logArray, acl, tenantId);
        }
      }
    } else {
      List<Acl> acls =
          manageDatabase.getHandleDbRequests().getSyncAcls(syncBackAcls.getSourceEnv(), tenantId);
      for (Acl acl : acls) {
        approveSyncBackAcls(syncBackAcls, resultMap, logArray, acl, tenantId);
      }
    }

    resultMap.put("syncbacklog", logArray);

    return resultMap;
  }

  private void approveSyncBackAcls(
      SyncBackAcls syncBackAcls,
      Map<String, List<String>> resultMap,
      List<String> logUpdateSyncBackTopics,
      Acl aclFound,
      int tenantId) {
    try {
      AclRequests aclReq = new AclRequests();
      copyProperties(aclFound, aclReq);
      aclReq.setReq_no(null);
      aclReq.setAcl_ip(aclFound.getAclip());
      aclReq.setAcl_ssl(aclFound.getAclssl());
      aclReq.setEnvironment(syncBackAcls.getTargetEnv());
      aclReq.setRequestingteam(aclFound.getTeamId());
      aclReq.setAclType("Create");
      aclReq.setUsername(getUserName());
      aclReq.setTenantId(tenantId);

      ResponseEntity<Map<String, String>> response =
          clusterApiService.approveAclRequests(aclReq, tenantId);

      Map<String, String> responseBody = response.getBody();
      String resultAclReq = responseBody.get("result");
      String resultAclNullCheck = Objects.requireNonNull(responseBody).get("result");
      if (!Objects.requireNonNull(resultAclNullCheck).contains("success")) {
        log.error("Error in creating acl {} {}", aclFound, responseBody);
        logUpdateSyncBackTopics.add(
            "Error in Acl creation. Acl:" + aclFound.getTopicname() + " " + resultAclReq);
      } else if (resultAclReq.contains("Acl already exists")) {
        logUpdateSyncBackTopics.add("Acl already exists " + aclFound.getTopicname());
      } else {
        if (!Objects.equals(syncBackAcls.getSourceEnv(), syncBackAcls.getTargetEnv())) {
          logUpdateSyncBackTopics.add("Acl added: " + aclFound.getTopicname());
          // Create request
          Map<String, String> resultMapReq =
              manageDatabase.getHandleDbRequests().requestForAcl(aclReq);
          if (resultMapReq.containsKey("aclId")) {
            Integer aclId = Integer.parseInt(resultMapReq.get("aclId"));
            aclReq.setReq_no(aclId);
            // Approve request
            String emptyJsonParams = "{}";
            manageDatabase
                .getHandleDbRequests()
                .updateAclRequest(aclReq, getUserName(), emptyJsonParams);
          }
        }
      }
    } catch (KlawException e) {
      log.error("Error in creating acl {} {}", e.getMessage(), aclFound);
      List<String> resultStatus = new ArrayList<>();
      resultStatus.add("Error :" + e.getMessage());
      resultMap.put("result", resultStatus);
    }
  }

  public List<AclRequestsModel> getAclRequests(
      String pageNo, String currentPage, String requestsType) {

    String userDetails = getUserName();
    int tenantId = commonUtilsService.getTenantId(userDetails);
    HandleDbRequests dbHandle = manageDatabase.getHandleDbRequests();
    List<AclRequests> aclReqs =
        dbHandle.getAllAclRequests(false, userDetails, "", requestsType, false, tenantId);

    // tenant filtering
    List<String> allowedEnvIdList = getEnvsFromUserId(userDetails);
    aclReqs =
        aclReqs.stream()
            .filter(aclRequest -> allowedEnvIdList.contains(aclRequest.getEnvironment()))
            .collect(Collectors.toList());

    aclReqs =
        aclReqs.stream()
            .sorted(Collections.reverseOrder(Comparator.comparing(AclRequests::getRequesttime)))
            .collect(Collectors.toList());

    aclReqs = getAclRequestsPaged(aclReqs, pageNo, currentPage, tenantId);

    return getAclRequestsModels(aclReqs, tenantId);
  }

  private List<AclRequestsModel> getAclRequestsModels(List<AclRequests> aclReqs, int tenantId) {
    List<AclRequestsModel> aclRequestsModels = new ArrayList<>();
    AclRequestsModel aclRequestsModel;

    List<String> approverRoles =
        rolesPermissionsControllerService.getApproverRoles("SUBSCRIPTIONS", tenantId);

    if (aclReqs != null)
      for (AclRequests aclRequests : aclReqs) {
        aclRequestsModel = new AclRequestsModel();
        copyProperties(aclRequests, aclRequestsModel);
        if (aclRequests.getAcl_ip() != null) {
          String[] aclListIp = aclRequests.getAcl_ip().split("<ACL>");
          aclRequestsModel.setAcl_ip(new ArrayList<>(Arrays.asList(aclListIp)));
        }

        if (aclRequests.getAcl_ssl() != null) {
          String[] aclListSsl = aclRequests.getAcl_ssl().split("<ACL>");
          aclRequestsModel.setAcl_ssl(new ArrayList<>(Arrays.asList(aclListSsl)));
        }
        aclRequestsModel.setTeamname(
            manageDatabase.getTeamNameFromTeamId(tenantId, aclRequests.getTeamId()));

        // show approving info only before approvals
        if (!RequestStatus.approved.name().equals(aclRequestsModel.getAclstatus())) {
          aclRequestsModel.setApprovingTeamDetails(
              updateApprovingInfo(
                  aclRequestsModel.getTopicname(),
                  aclRequestsModel.getAclType(),
                  aclRequestsModel.getRequestingteam(),
                  approverRoles,
                  aclRequestsModel.getUsername(),
                  tenantId));
        }

        aclRequestsModels.add(aclRequestsModel);
      }
    return aclRequestsModels;
  }

  private String updateApprovingInfo(
      String topicName,
      String aclType,
      Integer team,
      List<String> approverRoles,
      String requestor,
      int tenantId) {
    List<Topic> topicTeamsList =
        manageDatabase.getHandleDbRequests().getTopicTeam(topicName, tenantId);
    if (topicTeamsList.size() > 0) {
      Integer teamId = getFilteredTopicsForTenant(topicTeamsList).get(0).getTeamId();

      if ("Delete".equals(aclType)) teamId = team;
      List<UserInfo> userList =
          manageDatabase.getHandleDbRequests().selectAllUsersInfoForTeam(teamId, tenantId);

      StringBuilder approvingInfo =
          new StringBuilder(
              "Team : " + manageDatabase.getTeamNameFromTeamId(tenantId, teamId) + ", Users : ");

      for (UserInfo userInfo : userList) {
        if (approverRoles.contains(userInfo.getRole())
            && !Objects.equals(requestor, userInfo.getUsername()))
          approvingInfo.append(userInfo.getUsername()).append(",");
      }
      return String.valueOf(approvingInfo);
    }

    return "";
  }

  public List<AclRequestsModel> getAclRequestModelPaged(
      List<AclRequestsModel> origActivityList, String pageNo, String currentPage, int tenantId) {
    List<AclRequestsModel> newList = new ArrayList<>();

    if (origActivityList != null && origActivityList.size() > 0) {
      int totalRecs = origActivityList.size();
      int recsPerPage = 10;
      int totalPages = totalRecs / recsPerPage + (totalRecs % recsPerPage > 0 ? 1 : 0);

      pageNo = commonUtilsService.deriveCurrentPage(pageNo, currentPage, totalPages);

      int requestPageNo = Integer.parseInt(pageNo);
      int startVar = (requestPageNo - 1) * recsPerPage;
      int lastVar = (requestPageNo) * (recsPerPage);

      List<String> numList = new ArrayList<>();
      commonUtilsService.getAllPagesList(pageNo, currentPage, totalPages, numList);

      for (int i = 0; i < totalRecs; i++) {
        AclRequestsModel aclRequestsModel = origActivityList.get(i);
        if (i >= startVar && i < lastVar) {
          aclRequestsModel.setAllPageNos(numList);
          aclRequestsModel.setTotalNoPages("" + totalPages);
          aclRequestsModel.setCurrentPage(pageNo);
          aclRequestsModel.setTeamname(
              manageDatabase.getTeamNameFromTeamId(tenantId, aclRequestsModel.getTeamId()));
          aclRequestsModel.setEnvironmentName(
              getEnvDetails(aclRequestsModel.getEnvironment(), tenantId).getName());

          newList.add(aclRequestsModel);
        }
      }
    }

    return newList;
  }

  public List<AclRequests> getAclRequestsPaged(
      List<AclRequests> origActivityList, String pageNo, String currentPage, int tenantId) {
    List<AclRequests> newList = new ArrayList<>();

    if (origActivityList != null && origActivityList.size() > 0) {
      int totalRecs = origActivityList.size();
      int recsPerPage = 10;
      int totalPages = totalRecs / recsPerPage + (totalRecs % recsPerPage > 0 ? 1 : 0);

      pageNo = commonUtilsService.deriveCurrentPage(pageNo, currentPage, totalPages);
      int requestPageNo = Integer.parseInt(pageNo);
      int startVar = (requestPageNo - 1) * recsPerPage;
      int lastVar = (requestPageNo) * (recsPerPage);

      List<String> numList = new ArrayList<>();
      commonUtilsService.getAllPagesList(pageNo, currentPage, totalPages, numList);

      for (int i = 0; i < totalRecs; i++) {
        AclRequests activityLog = origActivityList.get(i);
        if (i >= startVar && i < lastVar) {
          activityLog.setAllPageNos(numList);
          activityLog.setTotalNoPages("" + totalPages);
          activityLog.setCurrentPage(pageNo);
          activityLog.setEnvironmentName(
              getEnvDetails(activityLog.getEnvironment(), tenantId).getName());

          newList.add(activityLog);
        }
      }
    }

    return newList;
  }

  public List<AclRequestsModel> getCreatedAclRequests(
      String pageNo, String currentPage, String requestsType) {
    log.debug("getCreatedAclRequests {} {}", pageNo, requestsType);
    String userDetails = getUserName();
    List<AclRequests> createdAclReqs;
    int tenantId = commonUtilsService.getTenantId(userDetails);

    // get requests relevant to your teams or all teams
    if (commonUtilsService.isNotAuthorizedUser(
        getPrincipal(), PermissionType.APPROVE_ALL_REQUESTS_TEAMS))
      createdAclReqs =
          manageDatabase
              .getHandleDbRequests()
              .getCreatedAclRequestsByStatus(userDetails, requestsType, false, tenantId);
    else
      createdAclReqs =
          manageDatabase
              .getHandleDbRequests()
              .getCreatedAclRequestsByStatus(userDetails, requestsType, true, tenantId);

    // tenant filtering
    List<String> allowedEnvIdList = getEnvsFromUserId(userDetails);
    createdAclReqs =
        createdAclReqs.stream()
            .filter(aclRequest -> allowedEnvIdList.contains(aclRequest.getEnvironment()))
            .collect(Collectors.toList());

    return getAclRequestModelPaged(
        updateCreatAclReqsList(createdAclReqs, tenantId), pageNo, currentPage, tenantId);
  }

  private List<AclRequestsModel> updateCreatAclReqsList(
      List<AclRequests> aclRequestsList, int tenantId) {
    List<AclRequestsModel> aclRequestsModels = getAclRequestsModels(aclRequestsList, tenantId);
    aclRequestsModels =
        aclRequestsModels.stream()
            .sorted(Comparator.comparing(AclRequestsModel::getRequesttime))
            .collect(Collectors.toList());

    return aclRequestsModels;
  }

  public String deleteAclRequests(String req_no) {
    try {
      if (commonUtilsService.isNotAuthorizedUser(
          getPrincipal(), PermissionType.REQUEST_CREATE_SUBSCRIPTIONS)) {
        return "{\"result\":\" Not Authorized. \"}";
      }
      log.info("deleteAclRequests {}", req_no);
      return "{\"result\":\""
          + manageDatabase
              .getHandleDbRequests()
              .deleteAclRequest(
                  Integer.parseInt(req_no), commonUtilsService.getTenantId(getUserName()))
          + "\"}";
    } catch (Exception e) {
      log.error("Exception ", e);
      return "{\"result\":\"failure " + e.toString() + "\"}";
    }
  }

  // this will create a delete subscription request
  public String createDeleteAclSubscriptionRequest(String req_no) {
    log.info("createDeleteAclSubscriptionRequest {}", req_no);
    String userDetails = getUserName();
    if (commonUtilsService.isNotAuthorizedUser(
        getPrincipal(), PermissionType.REQUEST_DELETE_SUBSCRIPTIONS)) {
      return "{\"result\":\" Not Authorized. \"}";
    }

    HandleDbRequests dbHandle = manageDatabase.getHandleDbRequests();

    Acl acl =
        dbHandle.selectSyncAclsFromReqNo(
            Integer.parseInt(req_no), commonUtilsService.getTenantId(getUserName()));

    if (!getEnvsFromUserId(userDetails).contains(acl.getEnvironment()))
      return "{\"result\":\"failure\"}";

    AclRequests aclReq = new AclRequests();

    copyProperties(acl, aclReq);
    aclReq.setAcl_ip(acl.getAclip());
    aclReq.setAcl_ssl(acl.getAclssl());

    aclReq.setUsername(userDetails);
    aclReq.setAclType("Delete");
    aclReq.setOtherParams(req_no);
    aclReq.setJsonParams(acl.getJsonParams());
    String execRes = manageDatabase.getHandleDbRequests().requestForAcl(aclReq).get("result");

    if ("success".equals(execRes)) {
      mailService.sendMail(
          aclReq.getTopicname(),
          aclReq.getTopictype(),
          "",
          userDetails,
          manageDatabase.getHandleDbRequests(),
          ACL_DELETE_REQUESTED,
          commonUtilsService.getLoginUrl());
    }
    return "{\"result\":\"" + execRes + "\"}";
  }

  public String approveAclRequests(String req_no) throws KlawException {
    log.info("approveAclRequests {}", req_no);
    String userDetails = getUserName();
    int tenantId = commonUtilsService.getTenantId(getUserName());
    if (commonUtilsService.isNotAuthorizedUser(
        getPrincipal(), PermissionType.APPROVE_SUBSCRIPTIONS)) {
      return "{\"result\":\" Not Authorized. \"}";
    }

    HandleDbRequests dbHandle = manageDatabase.getHandleDbRequests();
    AclRequests aclReq = dbHandle.selectAcl(Integer.parseInt(req_no), tenantId);

    if (Objects.equals(aclReq.getUsername(), userDetails))
      return "{\"result\":\"You are not allowed to approve your own subscription requests.\"}";

    if (!RequestStatus.created.name().equals(aclReq.getAclstatus())) {
      return "{\"result\":\"This request does not exist anymore.\"}";
    }

    // tenant filtering
    if (!getEnvsFromUserId(userDetails).contains(aclReq.getEnvironment()))
      return "{\"result\":\"Not Authorized\"}";

    String allIps = aclReq.getAcl_ip();
    String allSsl = aclReq.getAcl_ssl();
    if (aclReq.getReq_no() != null) {
      ResponseEntity<Map<String, String>> response = null;
      if (aclReq.getAcl_ip() != null) {
        String[] aclListIp = aclReq.getAcl_ip().split("<ACL>");
        for (String s : aclListIp) {
          aclReq.setAcl_ip(s);
          response = clusterApiService.approveAclRequests(aclReq, tenantId);
        }
      } else if (aclReq.getAcl_ssl() != null) {
        String[] aclListSsl = aclReq.getAcl_ssl().split("<ACL>");
        for (String s : aclListSsl) {
          aclReq.setAcl_ssl(s);
          response = clusterApiService.approveAclRequests(aclReq, tenantId);
        }
      }

      // set back all ips, principles
      aclReq.setAcl_ip(allIps);
      aclReq.setAcl_ssl(allSsl);

      String updateAclReqStatus;

      try {
        Map<String, String> responseBody = response.getBody();
        if (Objects.requireNonNull(Objects.requireNonNull(responseBody).get("result"))
            .contains("success")) {
          String jsonParams = "", aivenAclIdKey = "aivenaclid";
          if (responseBody.containsKey(aivenAclIdKey))
            jsonParams = "{\"" + aivenAclIdKey + "\":\"" + responseBody.get(aivenAclIdKey) + "\"}";
          updateAclReqStatus = dbHandle.updateAclRequest(aclReq, userDetails, jsonParams);
        } else return "{\"result\":\"failure\"}";
      } catch (Exception e) {
        log.error("Exception ", e);
        return "{\"result\":\"failure " + e.toString() + "\"}";
      }

      mailService.sendMail(
          aclReq.getTopicname(),
          aclReq.getTopictype(),
          "",
          aclReq.getUsername(),
          dbHandle,
          ACL_REQUEST_APPROVED,
          commonUtilsService.getLoginUrl());
      return "{\"result\":\"" + updateAclReqStatus + "\"}";
    } else return "{\"result\":\"Record not found !\"}";
  }

  public String declineAclRequests(String req_no, String reasonToDecline) {
    log.info("declineAclRequests {}", req_no);

    String userDetails = getUserName();
    if (commonUtilsService.isNotAuthorizedUser(
        getPrincipal(), PermissionType.APPROVE_SUBSCRIPTIONS)) {
      return "{\"result\":\" Not Authorized. \"}";
    }

    HandleDbRequests dbHandle = manageDatabase.getHandleDbRequests();
    AclRequests aclReq =
        dbHandle.selectAcl(Integer.parseInt(req_no), commonUtilsService.getTenantId(getUserName()));

    if (!RequestStatus.created.name().equals(aclReq.getAclstatus())) {
      return "{\"result\":\"This request does not exist anymore.\"}";
    }

    // tenant filtering
    if (!getEnvsFromUserId(userDetails).contains(aclReq.getEnvironment()))
      return "{\"result\":\"Not Authorized\"}";

    String updateAclReqStatus;

    if (aclReq.getReq_no() != null) {
      try {
        updateAclReqStatus = dbHandle.declineAclRequest(aclReq, userDetails);
        mailService.sendMail(
            aclReq.getTopicname(),
            aclReq.getTopictype(),
            reasonToDecline,
            aclReq.getUsername(),
            dbHandle,
            ACL_REQUEST_DENIED,
            commonUtilsService.getLoginUrl());

        return "{\"result\":\"" + updateAclReqStatus + "\"}";
      } catch (Exception e) {
        return "{\"result\":\"failure " + e.toString() + "\"}";
      }
    } else return "{\"result\":\"Record not found !\"}";
  }

  private List<Map<String, String>> getAclListFromCluster(
      String bootstrapHost,
      Env envSelected,
      String protocol,
      String clusterName,
      String topicNameSearch,
      int tenantId)
      throws KlawException {
    List<Map<String, String>> aclList;
    aclList =
        clusterApiService.getAcls(bootstrapHost, envSelected, protocol, clusterName, tenantId);
    return updateConsumerGroups(groupAcls(aclList, topicNameSearch, true), aclList);
  }

  private List<Map<String, String>> updateConsumerGroups(
      List<Map<String, String>> groupedList, List<Map<String, String>> clusterAclList) {
    List<Map<String, String>> updateList = new ArrayList<>(groupedList);

    for (Map<String, String> hMapGroupItem : groupedList) {
      for (Map<String, String> hMapItem : clusterAclList) {
        if ("READ".equals(hMapGroupItem.get("operation"))
            && "READ".equals(hMapItem.get("operation"))
            && "GROUP".equals(hMapItem.get("resourceType"))) {
          if (Objects.equals(hMapItem.get("host"), hMapGroupItem.get("host"))
              && Objects.equals(hMapItem.get("principle"), hMapGroupItem.get("principle"))) {
            Map<String, String> hashMap = new HashMap<>(hMapGroupItem);
            hashMap.put("consumerGroup", hMapItem.get("resourceName"));
            updateList.add(hashMap);
            break;
          }
        }
      }
    }
    return updateList;
  }

  private List<Map<String, String>> groupAcls(
      List<Map<String, String>> aclList, String topicNameSearch, boolean isSync) {

    return aclList.stream()
        .filter(
            hItem -> {
              if (isSync) {
                if (topicNameSearch != null) {
                  return "TOPIC".equals(hItem.get("resourceType"))
                      && hItem.get("resourceName").contains(topicNameSearch);
                } else return "TOPIC".equals(hItem.get("resourceType"));
              } else {
                return Objects.equals(hItem.get("resourceName"), topicNameSearch);
              }
            })
        .collect(Collectors.toList());
  }

  private List<Acl> getAclsFromSOT(
      String env, String topicNameSearch, boolean regex, int tenantId) {
    List<Acl> aclsFromSOT;
    if (!regex)
      aclsFromSOT =
          manageDatabase.getHandleDbRequests().getSyncAcls(env, topicNameSearch, tenantId);
    else {
      aclsFromSOT = manageDatabase.getHandleDbRequests().getSyncAcls(env, tenantId);
      List<Acl> topicFilteredList = aclsFromSOT;
      // Filter topics on topic name for search
      if (topicNameSearch != null && topicNameSearch.length() > 0) {
        final String topicSearchFilter = topicNameSearch;
        topicFilteredList =
            aclsFromSOT.stream()
                .filter(acl -> acl.getTopicname().contains(topicSearchFilter))
                .collect(Collectors.toList());
      }
      aclsFromSOT = topicFilteredList;
    }

    return aclsFromSOT;
  }

  private Map<String, String> getTopicPromotionEnv(
      String topicSearch, List<Topic> topics, int tenantId) {
    Map<String, String> hashMap = new HashMap<>();
    try {
      if (topics == null)
        topics = manageDatabase.getHandleDbRequests().getTopics(topicSearch, tenantId);

      hashMap.put("topicName", topicSearch);

      if (topics != null && topics.size() > 0) {
        List<String> envList = new ArrayList<>();
        List<String> finalEnvList = envList;
        topics.forEach(topic -> finalEnvList.add(topic.getEnvironment()));
        envList = finalEnvList;

        // tenant filtering
        String orderOfEnvs = mailService.getEnvProperty(tenantId, "ORDER_OF_ENVS");

        envList.sort(Comparator.comparingInt(orderOfEnvs::indexOf));

        String lastEnv = envList.get(envList.size() - 1);
        List<String> orderdEnvs = Arrays.asList(orderOfEnvs.split(","));

        if (orderdEnvs.indexOf(lastEnv) == orderdEnvs.size() - 1) {
          hashMap.put("status", "NO_PROMOTION"); // PRD
        } else {
          hashMap.put("status", "success");
          hashMap.put("sourceEnv", lastEnv);
          String targetEnv = orderdEnvs.get(orderdEnvs.indexOf(lastEnv) + 1);
          hashMap.put("targetEnv", getEnvDetails(targetEnv, tenantId).getName());
          hashMap.put("targetEnvId", targetEnv);
        }

        return hashMap;
      }
    } catch (Exception e) {
      log.error("getTopicPromotionEnv {}", e.getMessage());
      hashMap.put("status", "failure");
      hashMap.put("error", "Topic does not exist in any environment.");
    }

    return hashMap;
  }

  private List<Topic> getFilteredTopicsForTenant(List<Topic> topicsFromSOT) {
    // tenant filtering
    try {
      List<String> allowedEnvIdList = getEnvsFromUserId(getUserName());
      if (topicsFromSOT != null)
        topicsFromSOT =
            topicsFromSOT.stream()
                .filter(topic -> allowedEnvIdList.contains(topic.getEnvironment()))
                .collect(Collectors.toList());
    } catch (Exception exception) {
      log.error("No environments/clusters found.");
      return new ArrayList<>();
    }
    return topicsFromSOT;
  }

  public TopicOverview getAcls(String topicNameSearch) {
    log.debug("getAcls {}", topicNameSearch);
    String userDetails = getUserName();
    HandleDbRequests handleDb = manageDatabase.getHandleDbRequests();
    int tenantId = commonUtilsService.getTenantId(getUserName());
    String retrieveSchemasStr = manageDatabase.getKwPropertyValue(RETRIEVE_SCHEMAS_KEY, tenantId);
    boolean retrieveSchemas = "true".equals(retrieveSchemasStr);

    if (topicNameSearch != null) topicNameSearch = topicNameSearch.trim();
    else return null;

    Integer loggedInUserTeam = getMyTeamId(userDetails);

    List<Topic> topics = handleDb.getTopics(topicNameSearch, tenantId);

    // tenant filtering
    List<String> allowedEnvIdList = getEnvsFromUserId(userDetails);
    topics =
        topics.stream()
            .filter(topicObj -> allowedEnvIdList.contains(topicObj.getEnvironment()))
            .collect(Collectors.toList());

    TopicOverview topicOverview = new TopicOverview();

    if (topics.size() == 0) {
      topicOverview.setTopicExists(false);
      return topicOverview;
    } else topicOverview.setTopicExists(true);

    String syncCluster;
    String[] reqTopicsEnvs;
    ArrayList<String> reqTopicsEnvsList = new ArrayList<>();
    try {
      syncCluster = manageDatabase.getTenantConfig().get(tenantId).getBaseSyncEnvironment();
    } catch (Exception exception) {
      syncCluster = null;
    }

    try {
      String requestTopicsEnvs = mailService.getEnvProperty(tenantId, "REQUEST_TOPICS_OF_ENVS");
      reqTopicsEnvs = requestTopicsEnvs.split(",");
      reqTopicsEnvsList = new ArrayList<>(Arrays.asList(reqTopicsEnvs));
    } catch (Exception exception) {
      log.error("Error in getting req topic envs");
    }

    List<TopicInfo> topicInfoList = new ArrayList<>();
    ArrayList<TopicHistory> topicHistoryFromTopic;
    List<TopicHistory> topicHistoryList = new ArrayList<>();

    for (Topic topic : topics) {
      TopicInfo topicInfo = new TopicInfo();
      topicInfo.setCluster(getEnvDetails(topic.getEnvironment(), tenantId).getName());
      topicInfo.setClusterId(topic.getEnvironment());
      topicInfo.setNoOfPartitions(topic.getNoOfPartitions());
      topicInfo.setNoOfReplcias(topic.getNoOfReplcias());
      topicInfo.setTeamname(manageDatabase.getTeamNameFromTeamId(tenantId, topic.getTeamId()));

      if (syncCluster != null && syncCluster.equals(topic.getEnvironment())) {
        topicOverview.setTopicDocumentation(topic.getDocumentation());
        topicOverview.setTopicIdForDocumentation(topic.getTopicid());
      }

      if (topic.getHistory() != null) {
        try {
          topicHistoryFromTopic = OBJECT_MAPPER.readValue(topic.getHistory(), ArrayList.class);
          topicHistoryList.addAll(topicHistoryFromTopic);
        } catch (JsonProcessingException e) {
          log.error("Unable to parse topicHistory");
        }
      }

      topicInfoList.add(topicInfo);
    }

    if (topicOverview.getTopicIdForDocumentation() == null) {
      topicOverview.setTopicDocumentation(topics.get(0).getDocumentation());
      topicOverview.setTopicIdForDocumentation(topics.get(0).getTopicid());
    }

    topicOverview.setTopicHistoryList(topicHistoryList);

    List<Acl> aclsFromSOT = new ArrayList<>();
    List<AclInfo> aclInfo = new ArrayList<>();
    List<AclInfo> tmpAcl;
    List<Acl> prefixedAcls = new ArrayList<>();
    List<Acl> allPrefixedAcls;
    List<AclInfo> tmpAclPrefixed;
    List<AclInfo> prefixedAclsInfo = new ArrayList<>();

    List<Topic> topicsSearchList =
        manageDatabase.getHandleDbRequests().getTopicTeam(topicNameSearch, tenantId);

    // tenant filtering
    Integer topicOwnerTeam = getFilteredTopicsForTenant(topicsSearchList).get(0).getTeamId();

    for (TopicInfo topicInfo : topicInfoList) {
      aclsFromSOT.addAll(
          getAclsFromSOT(topicInfo.getClusterId(), topicNameSearch, false, tenantId));

      tmpAcl =
          applyFiltersAclsForSOT(loggedInUserTeam, aclsFromSOT, tenantId).stream()
              .collect(Collectors.groupingBy(AclInfo::getTopicname))
              .get(topicNameSearch);

      if (tmpAcl != null) aclInfo.addAll(tmpAcl);

      allPrefixedAcls = handleDb.getPrefixedAclsSOT(topicInfo.getClusterId(), tenantId);
      if (allPrefixedAcls != null && allPrefixedAcls.size() > 0) {
        for (Acl allPrefixedAcl : allPrefixedAcls) {
          if (topicNameSearch.startsWith(allPrefixedAcl.getTopicname())) {
            prefixedAcls.add(allPrefixedAcl);
          }
        }
        tmpAclPrefixed = applyFiltersAclsForSOT(loggedInUserTeam, prefixedAcls, tenantId);
        prefixedAclsInfo.addAll(tmpAclPrefixed);
      }

      // show edit button only for restricted envs
      if (Objects.equals(topicOwnerTeam, loggedInUserTeam)
          && reqTopicsEnvsList.contains(topicInfo.getClusterId())) {
        topicInfo.setShowEditTopic(true);
      }
    }

    aclInfo = aclInfo.stream().distinct().collect(Collectors.toList());

    List<AclInfo> transactionalAcls =
        aclInfo.stream()
            .filter(aclRec -> aclRec.getTransactionalId() != null)
            .collect(Collectors.toList());

    for (AclInfo aclInfo1 : aclInfo) {
      aclInfo1.setEnvironmentName(getEnvDetails(aclInfo1.getEnvironment(), tenantId).getName());
    }

    for (AclInfo aclInfo2 : prefixedAclsInfo) {
      aclInfo2.setEnvironmentName(getEnvDetails(aclInfo2.getEnvironment(), tenantId).getName());
    }

    topicOverview.setAclInfoList(aclInfo);
    if (prefixedAclsInfo.size() > 0) {
      topicOverview.setPrefixedAclInfoList(prefixedAclsInfo);
      topicOverview.setPrefixAclsExists(true);
    }
    if (transactionalAcls.size() > 0) {
      topicOverview.setTransactionalAclInfoList(transactionalAcls);
      topicOverview.setTxnAclsExists(true);
    }

    topicOverview.setTopicInfoList(topicInfoList);

    try {
      if (Objects.equals(topicOwnerTeam, loggedInUserTeam)) {
        topicOverview.setPromotionDetails(getTopicPromotionEnv(topicNameSearch, topics, tenantId));

        if (topicInfoList.size() > 0) {
          TopicInfo lastItem = topicInfoList.get(topicInfoList.size() - 1);
          lastItem.setTopicDeletable(
              aclInfo.stream()
                  .noneMatch(
                      aclItem -> Objects.equals(aclItem.getEnvironment(), lastItem.getCluster())));
          lastItem.setShowDeleteTopic(true);
        }
      } else {
        Map<String, String> hashMap = new HashMap<>();
        hashMap.put("status", "not_authorized");
        topicOverview.setPromotionDetails(hashMap);
      }
    } catch (Exception e) {
      Map<String, String> hashMap = new HashMap<>();
      hashMap.put("status", "not_authorized");
      topicOverview.setPromotionDetails(hashMap);
    }

    return topicOverview;
  }

  public TopicOverview getSchemaOfTopic(String topicNameSearch, String schemaVersionSearch) {
    HandleDbRequests handleDb = manageDatabase.getHandleDbRequests();
    int tenantId = commonUtilsService.getTenantId(getUserName());
    TopicOverview topicOverview = new TopicOverview();
    topicOverview.setTopicExists(true);
    boolean retrieveSchemas = true;
    updateAvroSchema(
        topicNameSearch, schemaVersionSearch, handleDb, retrieveSchemas, topicOverview, tenantId);
    return topicOverview;
  }

  private void updateAvroSchema(
      String topicNameSearch,
      String schemaVersionSearch,
      HandleDbRequests handleDb,
      boolean retrieveSchemas,
      TopicOverview topicOverview,
      int tenantId) {
    if (topicOverview.isTopicExists() && retrieveSchemas) {
      List<Map<String, String>> schemaDetails = new ArrayList<>();
      Map<String, String> schemaMap = new HashMap<>();
      List<Env> schemaEnvs = handleDb.selectAllSchemaRegEnvs(tenantId);
      Object dynamicObj;
      Map<String, Object> hashMapSchemaObj;
      String schemaOfObj;
      for (Env schemaEnv : schemaEnvs) {
        try {
          KwClusters kwClusters =
              manageDatabase
                  .getClusters(KafkaClustersType.SCHEMA_REGISTRY.value, tenantId)
                  .get(schemaEnv.getClusterId());
          SortedMap<Integer, Map<String, Object>> schemaObjects =
              clusterApiService.getAvroSchema(
                  kwClusters.getBootstrapServers(),
                  kwClusters.getProtocol(),
                  kwClusters.getClusterName(),
                  topicNameSearch,
                  tenantId);

          Integer latestSchemaVersion = schemaObjects.firstKey();
          Set<Integer> allVersions = schemaObjects.keySet();
          List<Integer> allVersionsList = new ArrayList<>(allVersions);

          try {
            if (schemaVersionSearch != null
                && latestSchemaVersion == Integer.parseInt(schemaVersionSearch)) {
              schemaVersionSearch = "";
            }
          } catch (NumberFormatException ignored) {
          }

          // get latest version
          if (schemaVersionSearch != null && schemaVersionSearch.equals("")) {
            hashMapSchemaObj = schemaObjects.get(latestSchemaVersion);
            schemaOfObj = (String) hashMapSchemaObj.get("schema");
            schemaMap.put("isLatest", "true");
            schemaMap.put("id", hashMapSchemaObj.get("id") + "");
            schemaMap.put("compatibility", hashMapSchemaObj.get("compatibility") + "");
            schemaMap.put("version", "" + latestSchemaVersion);

            if (schemaObjects.size() > 1) {
              schemaMap.put("showNext", "true");
              schemaMap.put("showPrev", "false");
              int indexOfVersion = allVersionsList.indexOf(latestSchemaVersion);
              schemaMap.put("nextVersion", "" + allVersionsList.get(indexOfVersion + 1));
            }
          } else {
            hashMapSchemaObj = schemaObjects.get(Integer.parseInt(schemaVersionSearch));
            schemaOfObj = (String) hashMapSchemaObj.get("schema");
            schemaMap.put("isLatest", "false");
            schemaMap.put("id", hashMapSchemaObj.get("id") + "");
            schemaMap.put("compatibility", hashMapSchemaObj.get("compatibility") + "");
            schemaMap.put("version", "" + schemaVersionSearch);

            if (schemaObjects.size() > 1) {
              int indexOfVersion = allVersionsList.indexOf(Integer.parseInt(schemaVersionSearch));
              if (indexOfVersion + 1 == allVersionsList.size()) {
                schemaMap.put("showNext", "false");
                schemaMap.put("showPrev", "true");
                schemaMap.put("prevVersion", "" + allVersionsList.get(indexOfVersion - 1));
              } else {
                schemaMap.put("showNext", "true");
                schemaMap.put("showPrev", "true");
                schemaMap.put("prevVersion", "" + allVersionsList.get(indexOfVersion - 1));
                schemaMap.put("nextVersion", "" + allVersionsList.get(indexOfVersion + 1));
              }
            }
          }

          schemaMap.put("env", schemaEnv.getName());
          dynamicObj = OBJECT_MAPPER.readValue(schemaOfObj, Object.class);
          schemaOfObj = WRITER_WITH_DEFAULT_PRETTY_PRINTER.writeValueAsString(dynamicObj);
          schemaMap.put("content", schemaOfObj);

          schemaDetails.add(schemaMap);
          topicOverview.setSchemaExists(true);
          log.info("Getting schema " + topicNameSearch);
        } catch (Exception e) {
          log.error(e.getMessage());
        }
      }
      if (topicOverview.isSchemaExists()) topicOverview.setSchemaDetails(schemaDetails);
    }
  }

  public List<AclInfo> getSyncAcls(
      String env, String pageNo, String currentPage, String topicNameSearch, String showAllAcls)
      throws KlawException {
    log.info(
        "getSyncAcls env: {} topicNameSearch: {} showAllAcls:{}",
        env,
        topicNameSearch,
        showAllAcls);
    boolean isReconciliation = !Boolean.parseBoolean(showAllAcls);
    int tenantId = commonUtilsService.getTenantId(getUserName());

    if (topicNameSearch != null) topicNameSearch = topicNameSearch.trim();

    if (commonUtilsService.isNotAuthorizedUser(getPrincipal(), PermissionType.SYNC_SUBSCRIPTIONS))
      return null;

    List<Map<String, String>> aclList;

    Env envSelected = getEnvDetails(env, tenantId);
    KwClusters kwClusters =
        manageDatabase
            .getClusters(KafkaClustersType.KAFKA.value, tenantId)
            .get(envSelected.getClusterId());
    aclList =
        getAclListFromCluster(
            kwClusters.getBootstrapServers(),
            envSelected,
            kwClusters.getProtocol(),
            kwClusters.getClusterName(),
            topicNameSearch,
            tenantId);

    List<Acl> aclsFromSOT = getAclsFromSOT(env, topicNameSearch, true, tenantId);

    topicCounter = 0;
    return getAclsList(
        pageNo,
        currentPage,
        applyFiltersAcls(
            env, aclList, aclsFromSOT, isReconciliation, tenantId, kwClusters.getKafkaFlavor()));
  }

  public List<AclInfo> getSyncBackAcls(
      String envId, String pageNo, String currentPage, String topicNameSearch, String teamName) {
    log.info("getSyncBackAcls {} {} {} {}", envId, pageNo, topicNameSearch, teamName);
    if (topicNameSearch != null) topicNameSearch = topicNameSearch.trim();

    int tenantId = commonUtilsService.getTenantId(getUserName());

    if (commonUtilsService.isNotAuthorizedUser(
        getPrincipal(), PermissionType.SYNC_BACK_SUBSCRIPTIONS)) return null;

    List<Acl> aclsFromSOT;

    if (topicNameSearch != null && topicNameSearch.trim().length() > 0)
      aclsFromSOT = getAclsFromSOT(envId, topicNameSearch, false, tenantId);
    else aclsFromSOT = getAclsFromSOT(envId, topicNameSearch, true, tenantId);

    List<AclInfo> aclInfoList;
    Integer loggedInUserTeam = getMyTeamId(getUserName());

    aclInfoList =
        getAclsList(
            pageNo, currentPage, applyFiltersAclsForSOT(loggedInUserTeam, aclsFromSOT, tenantId));

    return aclInfoList;
  }

  private List<AclInfo> applyFiltersAclsForSOT(
      Integer loggedInUserTeam, List<Acl> aclsFromSOT, int tenantId) {

    List<AclInfo> aclList = new ArrayList<>();
    AclInfo mp;

    for (Acl aclSotItem : aclsFromSOT) {
      mp = new AclInfo();
      mp.setEnvironment(aclSotItem.getEnvironment());
      mp.setEnvironmentName(getEnvDetails(aclSotItem.getEnvironment(), tenantId).getName());
      mp.setTopicname(aclSotItem.getTopicname());
      mp.setAcl_ip(aclSotItem.getAclip());
      mp.setAcl_ssl(aclSotItem.getAclssl());
      mp.setTransactionalId(aclSotItem.getTransactionalId());
      mp.setTeamname(manageDatabase.getTeamNameFromTeamId(tenantId, aclSotItem.getTeamId()));
      mp.setConsumergroup(aclSotItem.getConsumergroup());
      mp.setTopictype(aclSotItem.getTopictype());
      mp.setAclPatternType(aclSotItem.getAclPatternType());
      mp.setReq_no(aclSotItem.getReq_no() + "");
      if (aclSotItem.getTeamId() != null && aclSotItem.getTeamId().equals(loggedInUserTeam))
        mp.setShowDeleteAcl(true);

      if (aclSotItem.getAclip() != null || aclSotItem.getAclssl() != null) aclList.add(mp);
    }
    return aclList;
  }

  private List<AclInfo> applyFiltersAcls(
      String env,
      List<Map<String, String>> aclList,
      List<Acl> aclsFromSOT,
      boolean isReconciliation,
      int tenantId,
      String kafkaFlavor) {

    List<AclInfo> aclListMap = new ArrayList<>();
    List<String> teamList = new ArrayList<>();
    teamList = tenantFiltering(teamList);

    for (Map<String, String> aclListItem : aclList) {
      AclInfo mp = new AclInfo();
      mp.setEnvironment(env);

      mp.setPossibleTeams(teamList);
      mp.setTeamname("");

      String tmpPermType = aclListItem.get("operation");

      if ("WRITE".equals(tmpPermType)) mp.setTopictype("Producer");
      else if ("READ".equals(tmpPermType)) {
        mp.setTopictype("Consumer");
        if (aclListItem.get("consumerGroup") != null)
          mp.setConsumergroup(aclListItem.get("consumerGroup"));
        else continue;
      }

      if ("topic".equalsIgnoreCase(aclListItem.get("resourceType")))
        mp.setTopicname(aclListItem.get("resourceName"));

      mp.setAcl_ip(aclListItem.get("host"));
      mp.setAcl_ssl(aclListItem.get("principle"));

      for (Acl aclSotItem : aclsFromSOT) {
        String acl_ssl = aclSotItem.getAclssl();
        String acl_host = aclSotItem.getAclip();

        if (acl_ssl == null || acl_ssl.equals("")) acl_ssl = "User:*";
        else {
          if (!KafkaFlavors.AIVEN_FOR_APACHE_KAFKA.value.equals(kafkaFlavor)
              && !"User:*".equals(acl_ssl)
              && !acl_ssl.startsWith("User:")) {
            acl_ssl = "User:" + acl_ssl;
          }
        }

        if (acl_host == null || acl_host.equals("")) acl_host = "*";

        if (aclSotItem.getTopicname() != null
            && Objects.equals(aclListItem.get("resourceName"), aclSotItem.getTopicname())
            && Objects.equals(aclListItem.get("host"), acl_host)
            && Objects.equals(aclListItem.get("principle"), acl_ssl)
            && Objects.equals(aclSotItem.getTopictype(), mp.getTopictype())) {
          mp.setTeamname(manageDatabase.getTeamNameFromTeamId(tenantId, aclSotItem.getTeamId()));
          mp.setReq_no(aclSotItem.getReq_no() + "");
          break;
        }
      }

      if (mp.getTeamname() == null) mp.setTeamname("Unknown");

      if (isReconciliation) {
        if ("Unknown".equals(mp.getTeamname()) || "".equals(mp.getTeamname())) aclListMap.add(mp);
      } else {
        if (teamList.contains(mp.getTeamname())) aclListMap.add(mp);
        else if ("Unknown".equals(mp.getTeamname()) || "".equals(mp.getTeamname()))
          aclListMap.add(mp);
      }
    }
    return aclListMap;
  }

  private List<String> tenantFiltering(List<String> teamList) {
    if (!commonUtilsService.isNotAuthorizedUser(
            getPrincipal(), PermissionType.SYNC_BACK_SUBSCRIPTIONS)
        || !commonUtilsService.isNotAuthorizedUser(getPrincipal(), PermissionType.SYNC_TOPICS)
        || !commonUtilsService.isNotAuthorizedUser(
            getPrincipal(), PermissionType.SYNC_SUBSCRIPTIONS)
        || !commonUtilsService.isNotAuthorizedUser(
            getPrincipal(), PermissionType.SYNC_BACK_TOPICS)) {
      // tenant filtering
      int tenantId = commonUtilsService.getTenantId(getUserName());

      List<Team> teams = manageDatabase.getHandleDbRequests().selectAllTeams(tenantId);

      teams =
          teams.stream()
              .filter(t -> Objects.equals(t.getTenantId(), tenantId))
              .collect(Collectors.toList());
      List<String> teamListUpdated = new ArrayList<>();
      for (Team teamsItem : teams) {
        teamListUpdated.add(teamsItem.getTeamname());
      }
      teamList = teamListUpdated;
    }
    return teamList;
  }

  private List<AclInfo> getAclsList(String pageNo, String currentPage, List<AclInfo> aclListMap) {
    List<AclInfo> aclListMapUpdated = new ArrayList<>();

    int totalRecs = aclListMap.size();
    int recsPerPage = 20;
    int totalPages =
        aclListMap.size() / recsPerPage + (aclListMap.size() % recsPerPage > 0 ? 1 : 0);

    pageNo = commonUtilsService.deriveCurrentPage(pageNo, currentPage, totalPages);
    int requestPageNo = Integer.parseInt(pageNo);
    int startVar = (requestPageNo - 1) * recsPerPage;
    int lastVar = (requestPageNo) * (recsPerPage);

    List<String> numList = new ArrayList<>();
    commonUtilsService.getAllPagesList(pageNo, currentPage, totalPages, numList);

    for (int i = 0; i < totalRecs; i++) {
      int counterInc = counterIncrement();
      if (i >= startVar && i < lastVar) {
        AclInfo mp = aclListMap.get(i);
        mp.setSequence(counterInc + "");

        mp.setTotalNoPages(totalPages + "");
        mp.setCurrentPage(pageNo);
        mp.setAllPageNos(numList);

        aclListMapUpdated.add(mp);
      }
    }
    return aclListMapUpdated;
  }

  private int topicCounter = 0;

  private int counterIncrement() {
    topicCounter++;
    return topicCounter;
  }

  private String getUserName() {
    return mailService.getUserName(
        SecurityContextHolder.getContext().getAuthentication().getPrincipal());
  }

  private boolean validateTeamConsumerGroup(Integer teamId, String consumerGroup, int tenantId) {
    List<Acl> acls = manageDatabase.getHandleDbRequests().getUniqueConsumerGroups(tenantId);

    for (Acl acl : acls) {
      if (!Objects.equals(acl.getTeamId(), teamId)
          && acl.getConsumergroup() != null
          && Objects.equals(acl.getConsumergroup(), consumerGroup)) {
        return true;
      }
    }
    return false;
  }

  public Env getEnvDetails(String envId, int tenantId) {

    Optional<Env> envFound =
        manageDatabase.getKafkaEnvList(tenantId).stream()
            .filter(env -> Objects.equals(env.getId(), envId))
            .findFirst();
    return envFound.orElse(null);
  }

  // based on tenants
  private List<String> getEnvsFromUserId(String userName) {
    int tenantId = commonUtilsService.getTenantId(userName);
    Integer myTeamId = getMyTeamId(userName);
    return manageDatabase.getTeamsAndAllowedEnvs(myTeamId, tenantId);
  }

  public List<Map<String, String>> getConsumerOffsets(
      String envId, String consumerGroupId, String topicName) {
    List<Map<String, String>> consumerOffsetInfoList = new ArrayList<>();
    int tenantId = commonUtilsService.getTenantId(getUserName());
    try {
      String bootstrapHost =
          manageDatabase
              .getClusters(KafkaClustersType.KAFKA.value, tenantId)
              .get(getEnvDetails(envId, tenantId).getClusterId())
              .getBootstrapServers();
      consumerOffsetInfoList =
          clusterApiService.getConsumerOffsets(
              bootstrapHost,
              manageDatabase
                  .getClusters(KafkaClustersType.KAFKA.value, tenantId)
                  .get(getEnvDetails(envId, tenantId).getClusterId())
                  .getProtocol(),
              manageDatabase
                  .getClusters(KafkaClustersType.KAFKA.value, tenantId)
                  .get(getEnvDetails(envId, tenantId).getClusterId())
                  .getClusterName(),
              topicName,
              consumerGroupId,
              tenantId);
    } catch (Exception e) {
      log.error("Ignoring error while retrieving consumer offsets {} ", e.toString());
    }

    return consumerOffsetInfoList;
  }

  private Object getPrincipal() {
    return SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  private Integer getMyTeamId(String userName) {
    return manageDatabase.getHandleDbRequests().getUsersInfo(userName).getTeamId();
  }
}
