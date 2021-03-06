/*
 * SonarLint Daemon - ITs - Tests
 * Copyright (C) 2016-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package its;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.locator.FileLocation;
import com.sonar.orchestrator.locator.MavenLocation;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import its.tools.ItUtils;
import its.tools.SonarlintDaemon;
import its.tools.SonarlintProject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.users.CreateRequest;
import org.sonarsource.sonarlint.daemon.proto.ConnectedSonarLintGrpc;
import org.sonarsource.sonarlint.daemon.proto.ConnectedSonarLintGrpc.ConnectedSonarLintBlockingStub;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ConnectedAnalysisReq;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ConnectedAnalysisReq.Builder;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ConnectedConfiguration;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.InputFile;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.Issue;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.LogEvent;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ModuleUpdateReq;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ServerConfig;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ServerConfig.Credentials;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.StorageState;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.Void;
import org.sonarsource.sonarlint.daemon.proto.StandaloneSonarLintGrpc;

import static its.tools.ItUtils.SONAR_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

public class ConnectedDaemonTest {
  private static final String PROJECT_KEY_JAVASCRIPT = "sample-javascript";
  private static final String SONARLINT_USER = "sonarlint";
  private static final String SONARLINT_PWD = "sonarlintpwd";
  private static final String STORAGE_ID = "storage";

  @ClassRule
  public static Orchestrator ORCHESTRATOR = Orchestrator.builderEnv()
    .setSonarVersion(SONAR_VERSION).addPlugin(MavenLocation.of("org.sonarsource.javascript", "sonar-javascript-plugin", ItUtils.javascriptVersion))
    // With recent version of SonarJS, SonarTS is required
    .addPlugin(MavenLocation.of("org.sonarsource.typescript", "sonar-typescript-plugin", ItUtils.typescriptVersion))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/javascript-sonarlint.xml"))
    .build();

  @ClassRule
  public static TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public SonarlintDaemon daemon = new SonarlintDaemon();

  @Rule
  public SonarlintProject clientTools = new SonarlintProject();

  @Rule
  public ExpectedException exception = ExpectedException.none();

  private static WsClient adminWsClient;
  private static Path sonarUserHome;

  @BeforeClass
  public static void prepare() throws Exception {
    adminWsClient = newAdminWsClient(ORCHESTRATOR);
    sonarUserHome = temp.newFolder().toPath();

    adminWsClient.users().create(new CreateRequest().setLogin(SONARLINT_USER).setPassword(SONARLINT_PWD).setName("SonarLint"));

    ORCHESTRATOR.getServer().provisionProject(PROJECT_KEY_JAVASCRIPT, "Sample Javascript");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(PROJECT_KEY_JAVASCRIPT, "js", "SonarLint IT Javascript");
  }

  public static WsClient newAdminWsClient(Orchestrator orchestrator) {
    com.sonar.orchestrator.container.Server server = orchestrator.getServer();
    return WsClientFactories.getDefault().newClient(HttpConnector.newBuilder()
      .url(server.getUrl())
      .credentials(com.sonar.orchestrator.container.Server.ADMIN_LOGIN, com.sonar.orchestrator.container.Server.ADMIN_PASSWORD)
      .build());
  }

  @Before
  public void start() {
    FileUtils.deleteQuietly(sonarUserHome.toFile());
    daemon.install();
  }

  @Test
  public void testNormal() throws InterruptedException, IOException {
    daemon.run();
    daemon.waitReady();
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8050)
      .usePlaintext(true)
      .build();

    LogCollector logs = new LogCollector();
    ConnectedSonarLintBlockingStub sonarlint = ConnectedSonarLintGrpc.newBlockingStub(channel);

    // REGISTER
    sonarlint.start(createConnectedConfig());

    // STATE
    assertThat(sonarlint.getState(Void.newBuilder().build()).getState()).isEqualTo(StorageState.State.NEVER_UPDATED);

    // UPDATE GLOBAL
    ServerConfig serverConfig = ServerConfig.newBuilder()
      .setHostUrl(ORCHESTRATOR.getServer().getUrl())
      .setCredentials(Credentials.newBuilder()
        .setLogin(SONARLINT_USER)
        .setPassword(SONARLINT_PWD)
        .build())
      .build();

    sonarlint.update(serverConfig);

    // STATE
    assertThat(sonarlint.getState(Void.newBuilder().build()).getState()).isEqualTo(StorageState.State.UPDATED);

    // UPDATE MODULE
    ModuleUpdateReq moduleUpdate = ModuleUpdateReq.newBuilder()
      .setModuleKey(PROJECT_KEY_JAVASCRIPT)
      .setServerConfig(serverConfig)
      .build();
    sonarlint.updateModule(moduleUpdate);

    // ANALYSIS
    ClientCall<Void, LogEvent> call = getLogs(logs, channel);
    Iterator<Issue> issues = sonarlint.analyze(createAnalysisConfig(PROJECT_KEY_JAVASCRIPT));

    assertThat(issues).toIterable().hasSize(1);
    call.cancel(null, null);

    channel.shutdownNow();
    channel.awaitTermination(2, TimeUnit.SECONDS);
  }

  @Test
  public void testPort() throws IOException {
    daemon.run("--port", "8051");
    daemon.waitReady();

    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8051)
      .usePlaintext(true)
      .build();

    ConnectedSonarLintBlockingStub sonarlint = ConnectedSonarLintGrpc.newBlockingStub(channel);
    sonarlint.start(createConnectedConfig());
  }

  @Test
  public void testError() throws IOException {
    daemon.run();
    daemon.waitReady();
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8050)
      .usePlaintext(true)
      .build();

    ConnectedSonarLintBlockingStub sonarlint = ConnectedSonarLintGrpc.newBlockingStub(channel);
    sonarlint.start(createConnectedConfig());

    // Analyze without update -> error
    Iterator<Issue> analyze = sonarlint.analyze(createAnalysisConfig(PROJECT_KEY_JAVASCRIPT));

    exception.expectMessage("Storage of server 'storage' requires an update");
    exception.expect(StatusRuntimeException.class);
    analyze.hasNext();

    sonarlint.shutdown(null);
  }

  private ClientCall<Void, LogEvent> getLogs(LogCollector collector, Channel channel) {
    ClientCall<Void, LogEvent> call = channel.newCall(StandaloneSonarLintGrpc.getStreamLogsMethod(), CallOptions.DEFAULT);
    call.start(collector, new Metadata());
    call.sendMessage(Void.newBuilder().build());
    call.halfClose();
    call.request(Integer.MAX_VALUE);
    return call;
  }

  private static ConnectedConfiguration createConnectedConfig() throws IOException {
    return ConnectedConfiguration.newBuilder()
      .setStorageId(STORAGE_ID)
      .setHomePath(temp.newFolder().toString())
      .build();
  }

  private ConnectedAnalysisReq createAnalysisConfig(String projectName) throws IOException {
    Path projectPath = clientTools.deployProject(projectName);
    List<Path> sourceFiles = clientTools.collectAllFiles(projectPath);
    Builder builder = ConnectedAnalysisReq.newBuilder();

    for (Path p : sourceFiles) {
      InputFile file = InputFile.newBuilder()
        .setCharset(StandardCharsets.UTF_8.name())
        .setPath(p.toAbsolutePath().toString())
        .setIsTest(false)
        .build();
      builder.addFile(file);
    }
    return builder
      .setBaseDir(projectPath.toAbsolutePath().toString())
      .setModuleKey(PROJECT_KEY_JAVASCRIPT)
      .putAllProperties(Collections.singletonMap("sonar.java.binaries",
        new File("projects/sample-java/target/classes").getAbsolutePath()))
      .build();
  }

  private static class LogCollector extends ClientCall.Listener<LogEvent> {
    private final List<LogEvent> list = Collections.synchronizedList(new LinkedList<LogEvent>());

    @Override
    public void onMessage(LogEvent log) {
      list.add(log);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      System.out.println("LOGS CLOSED " + status);
    }

    public String getLogsAndClear() {
      StringBuilder builder = new StringBuilder();
      synchronized (list) {
        for (LogEvent e : list) {
          if (e.getIsDebug()) {
            continue;
          }

          builder.append(e.getLog()).append("\n");
        }
        // list.clear();
      }

      return builder.toString();
    }

    public List<LogEvent> get() {
      return list;
    }
  }

}
