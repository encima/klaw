package io.aiven.klaw.controller;

import io.aiven.klaw.model.SyncBackTopics;
import io.aiven.klaw.model.SyncTopicUpdates;
import io.aiven.klaw.model.SyncTopicsBulk;
import io.aiven.klaw.model.TopicInfo;
import io.aiven.klaw.service.TopicSyncControllerService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class TopicSyncController {

  @Autowired private TopicSyncControllerService topicSyncControllerService;

  @PostMapping(value = "/updateSyncTopics")
  public ResponseEntity<Map<String, String>> updateSyncTopics(
      @RequestBody List<SyncTopicUpdates> syncTopicUpdates) {
    Map<String, String> updateSyncTopicsResult =
        topicSyncControllerService.updateSyncTopics(syncTopicUpdates);
    return new ResponseEntity<>(updateSyncTopicsResult, HttpStatus.OK);
  }

  @PostMapping(value = "/updateSyncTopicsBulk")
  public ResponseEntity<Map<String, List<String>>> updateSyncTopicsBulk(
      @RequestBody SyncTopicsBulk syncTopicsBulk) {
    Map<String, List<String>> updateSyncTopicsBulkResult =
        topicSyncControllerService.updateSyncTopicsBulk(syncTopicsBulk);
    return new ResponseEntity<>(updateSyncTopicsBulkResult, HttpStatus.OK);
  }

  // sync back topics
  @RequestMapping(
      value = "/getTopicsRowView",
      method = RequestMethod.GET,
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<List<TopicInfo>> getTopicsRowView(
      @RequestParam("env") String envId,
      @RequestParam("pageNo") String pageNo,
      @RequestParam(value = "currentPage", defaultValue = "") String currentPage,
      @RequestParam(value = "topicnamesearch", required = false) String topicNameSearch,
      @RequestParam(value = "teamName", required = false) String teamName,
      @RequestParam(value = "topicType", required = false) String topicType)
      throws Exception {

    return new ResponseEntity<>(
        topicSyncControllerService.getTopicsRowView(
            envId, pageNo, currentPage, topicNameSearch, teamName, topicType),
        HttpStatus.OK);
  }

  @PostMapping(value = "/updateSyncBackTopics")
  public ResponseEntity<Map<String, List<String>>> updateSyncBackTopics(
      @RequestBody SyncBackTopics syncBackTopics) {
    Map<String, List<String>> updateSyncTopicsResult =
        topicSyncControllerService.updateSyncBackTopics(syncBackTopics);
    return new ResponseEntity<>(updateSyncTopicsResult, HttpStatus.OK);
  }

  @RequestMapping(
      value = "/getSyncTopics",
      method = RequestMethod.GET,
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<Map<String, Object>> getSyncTopics(
      @RequestParam("env") String envId,
      @RequestParam("pageNo") String pageNo,
      @RequestParam(value = "currentPage", defaultValue = "") String currentPage,
      @RequestParam(value = "topicnamesearch", required = false) String topicNameSearch,
      @RequestParam(value = "showAllTopics", defaultValue = "false", required = false)
          String showAllTopics,
      @RequestParam(value = "isBulkOption", defaultValue = "false", required = false)
          String isBulkOption)
      throws Exception {
    if (Boolean.parseBoolean(showAllTopics))
      return new ResponseEntity<>(
          topicSyncControllerService.getSyncTopics(
              envId,
              pageNo,
              currentPage,
              topicNameSearch,
              showAllTopics,
              Boolean.parseBoolean(isBulkOption)),
          HttpStatus.OK);
    else
      return new ResponseEntity<>(
          topicSyncControllerService.getReconTopics(
              envId,
              pageNo,
              currentPage,
              topicNameSearch,
              showAllTopics,
              Boolean.parseBoolean(isBulkOption)),
          HttpStatus.OK);
  }
}
