package io.aiven.klaw.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aiven.klaw.UtilMethods;
import io.aiven.klaw.model.ServerConfigProperties;
import io.aiven.klaw.service.ServerConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(SpringExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ServerConfigControllerTest {

  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  @MockBean private ServerConfigService serverConfigService;

  UtilMethods utilMethods;
  private MockMvc mvc;

  @BeforeEach
  public void setUp() {
    ServerConfigController serverConfigController = new ServerConfigController();
    mvc = MockMvcBuilders.standaloneSetup(serverConfigController).dispatchOptions(true).build();
    utilMethods = new UtilMethods();
    ReflectionTestUtils.setField(
        serverConfigController, "serverConfigService", serverConfigService);
  }

  @Test
  @Order(1)
  public void getAllServerConfig() throws Exception {
    List<ServerConfigProperties> serverConfigPropertiesList = utilMethods.getServerConfig();
    when(serverConfigService.getAllProps()).thenReturn(serverConfigPropertiesList);

    String res =
        mvc.perform(
                MockMvcRequestBuilders.get("/getAllServerConfig")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<ServerConfigProperties> response = OBJECT_MAPPER.readValue(res, List.class);
    assertThat(response).hasSize(1);
  }
}
