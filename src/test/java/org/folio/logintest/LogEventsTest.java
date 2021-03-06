package org.folio.logintest;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import io.restassured.RestAssured;
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.folio.rest.RestVerticle;
import org.folio.rest.client.TenantClient;
import org.folio.rest.jaxrs.model.Config;
import org.folio.rest.jaxrs.model.Configurations;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ws.rs.core.MediaType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static junit.framework.TestCase.assertTrue;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TENANT;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TOKEN;
import static org.folio.util.LoginConfigUtils.*;

@RunWith(VertxUnitRunner.class)
public class LogEventsTest {

  private static final String TENANT_ID = "diku";
  private static final String OKAPI_TOKEN_VAL = "test_token";
  private static final String OKAPI_URL = "x-okapi-url";
  private static final String HTTP_PORT = "http.port";

  private static final String EVENT_LOG_API_CODE_RESET_PASSWORD = "RESET_PASSWORD";

  private static RequestSpecification request;
  private static String restPath;
  private static Vertx vertx;

  @Rule
  public WireMockRule userMockServer = new WireMockRule(
    WireMockConfiguration.wireMockConfig()
      .dynamicPort()
      .notifier(new ConsoleNotifier(true)));

  @BeforeClass
  public static void setUpClass(final TestContext context) {
    Async async = context.async();
    vertx = Vertx.vertx();
    int port = NetworkUtils.nextFreePort();
    Headers headers = new Headers(
      new Header(OKAPI_HEADER_TENANT, TENANT_ID),
      new Header(OKAPI_HEADER_TOKEN, OKAPI_TOKEN_VAL));
    restPath = System.getProperty("org.folio.event.config.rest.path", "/authn/log/events");
    request = RestAssured.given()
      .port(port)
      .contentType(MediaType.APPLICATION_JSON)
      .headers(headers);

    try {
      PostgresClient.setIsEmbedded(true);
      PostgresClient.getInstance(vertx).startEmbeddedPostgres();
    } catch (Exception e) {
      context.fail(e);
    }

    TenantClient tenantClient = new TenantClient("localhost", port, TENANT_ID, OKAPI_TOKEN_VAL);
    DeploymentOptions restDeploymentOptions = new DeploymentOptions()
      .setConfig(new JsonObject().put(HTTP_PORT, port));
    vertx.deployVerticle(RestVerticle.class.getName(), restDeploymentOptions,
      res -> {
        try {
          tenantClient.postTenant(null, handler -> async.complete());
        } catch (Exception e) {
          context.fail(e);
        }
      });
  }

  @AfterClass
  public static void tearDownClass(final TestContext context) {
    Async async = context.async();
    vertx.close(context.asyncAssertSuccess(res -> {
      PostgresClient.stopEmbeddedPostgres();
      async.complete();
    }));
  }

  @Before
  public void setUp(TestContext context) {
    Async async = context.async();
    PostgresClient.getInstance(vertx, TENANT_ID).delete(SNAPSHOTS_TABLE_EVENT_LOGS, new Criterion(), event -> {
      if (event.failed()) {
        context.fail(event.cause());
      } else {
        async.complete();
      }
    });
  }

  @Test
  public void testRestAPIWhenLoggingOff() {
    // create mod-config
    int mockServerPort = userMockServer.port();
    Config config = createConfig(EVENT_LOG_API_MODULE, EVENT_LOG_API_CODE_STATUS, false);
    initModConfigStub(mockServerPort, initLoggingConfigurations(config));
    String okapiUrl = "http://localhost:" + mockServerPort;

    JsonObject logEven = getLogEven("tenant", UUID.randomUUID().toString(), RandomStringUtils.randomAlphabetic(10));
    requestPostLogEvent(logEven, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    requestGetLogEvent(okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    requestDeleteLogEventById(UUID.randomUUID().toString(), okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);
  }

  @Test
  public void testRestAPIWhenLoggingOn() {
    // create mod-config
    int mockServerPort = userMockServer.port();
    Config config = createConfig(EVENT_LOG_API_MODULE, EVENT_LOG_API_CODE_STATUS, true);
    initModConfigStub(mockServerPort, initLoggingConfigurations(config));
    String okapiUrl = "http://localhost:" + mockServerPort;

    String userId = UUID.randomUUID().toString();
    JsonObject logEven = getLogEven("tenant", userId, RandomStringUtils.randomAlphabetic(10));
    requestPostLogEvent(logEven, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    requestGetLogEvent(okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_OK);

    requestDeleteLogEventById(userId, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);
  }

  @Test
  public void testRestAPIPostDelete() {
    // create mod-config
    int mockServerPort = userMockServer.port();
    Config configGlobal = createConfig(EVENT_LOG_API_MODULE, EVENT_LOG_API_CODE_STATUS, true);
    Config configReset = createConfig(EVENT_LOG_API_MODULE, EVENT_LOG_API_CODE_RESET_PASSWORD, true);
    initModConfigStub(mockServerPort, initLoggingConfigurations(configGlobal, configReset));
    String okapiUrl = "http://localhost:" + mockServerPort;
    String userId = UUID.randomUUID().toString();

    JsonObject logEven = getLogEven("tenant", userId, EVENT_LOG_API_CODE_RESET_PASSWORD);
    Response response = requestPostLogEvent(logEven, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract()
      .response();
    assertTrue(response.getBody().print().contains("was successfully saved to event log"));

    response = requestDeleteLogEventById(userId, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .extract()
      .response();
    assertTrue(response.getBody().print().contains("deleted from event log"));
  }

  @Test
  public void testRestAPIWhenLoggingOnWithResetPassword() {
    // create mod-config
    int mockServerPort = userMockServer.port();
    Config configGlobal = createConfig(EVENT_LOG_API_MODULE, EVENT_LOG_API_CODE_STATUS, true);
    Config configReset = createConfig(EVENT_LOG_API_MODULE, EVENT_LOG_API_CODE_RESET_PASSWORD, true);
    initModConfigStub(mockServerPort, initLoggingConfigurations(configGlobal, configReset));
    String okapiUrl = "http://localhost:" + mockServerPort;
    String userId = UUID.randomUUID().toString();

    JsonObject logEven = getLogEven("tenant", userId, EVENT_LOG_API_CODE_RESET_PASSWORD);
    Response response = requestPostLogEvent(logEven, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract().response();
    assertTrue(response.getBody().print().contains("was successfully saved to event log"));

    requestGetLogEvent(okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_OK);

    requestDeleteLogEventById(userId, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_OK);
  }

  @Test
  public void testRestAPIWithoutLogConfig() {
    // create mod-config
    int mockServerPort = userMockServer.port();
    Config config = createConfig(RandomStringUtils.randomAlphabetic(10), EVENT_LOG_API_CODE_STATUS, false);
    initModConfigStub(mockServerPort, initLoggingConfigurations(config));
    String okapiUrl = "http://localhost:" + mockServerPort;
    String id = UUID.randomUUID().toString();

    // test post
    JsonObject logEven = getLogEven(TENANT_ID, id, RandomStringUtils.randomAlphabetic(10));
    requestPostLogEvent(logEven, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    // test get
    requestGetLogEvent(okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    // test delete
    requestDeleteLogEventById(id, okapiUrl)
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);
  }

  private Response requestPostLogEvent(JsonObject expectedEntity, String okapiUrl) {
    return request.body(expectedEntity.toString())
      .header(new Header(OKAPI_URL, okapiUrl))
      .when()
      .post(restPath);
  }

  private Response requestGetLogEvent(String okapiUrl) {
    return request
      .header(new Header(OKAPI_URL, okapiUrl))
      .when()
      .get(restPath);
  }

  private Response requestDeleteLogEventById(String userId, String okapiUrl) {
    return request
      .header(new Header(OKAPI_URL, okapiUrl))
      .when()
      .delete(restPath + "/" + userId);
  }

  private void initModConfigStub(int port, Configurations configurations) {
    String urlTemplate = "/configurations/entries?query=module==%s";
    UrlPattern urlPattern = urlEqualTo(String.format(urlTemplate, EVENT_LOG_API_MODULE));
    MappingBuilder builder = get(urlPattern);
    stubFor(builder.willReturn(aResponse()
      .withHeader("Content-Type", "application/json")
      .withHeader("x-okapi-token", "x-okapi-token-TEST")
      .withHeader("x-okapi-url", "http://localhost:" + port)
      .withBody(JsonObject.mapFrom(configurations).toString())));
  }

  private JsonObject getLogEven(String tenant, String userId, String eventCode) {
    return new JsonObject()
      .put("tenant", tenant)
      .put("userId", userId)
      .put("eventCode", eventCode);
  }

  private Configurations initLoggingConfigurations(Config... configs) {
    Configurations configurations = new Configurations();
    int total = 0;
    if (!Objects.isNull(configs)) {
      List<Config> configList = Arrays.stream(configs).collect(Collectors.toList());
      total = configList.size();
      configurations.setConfigs(configList);
    }
    configurations.setTotalRecords(total);
    return configurations;
  }

  private static Config createConfig(String module, String code, boolean isEnable) {
    Config config = new Config();
    config.setModule(module);
    config.setCode(code);
    config.setEnabled(isEnable);
    return config;
  }
}
