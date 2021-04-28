/*
 * SonarLint Daemon Implementation
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
package org.sonarlint.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class UtilsTest {
  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private String sonarlintHome;

  @Test
  public void test() {
    assertThat(Utils.getStandaloneHome()).isNotNull();
  }

  @Before
  public void backupProps() {
    sonarlintHome = System.getProperty("sonarlint.home");
  }

  @After
  public void restoreProps() {
    if (sonarlintHome != null) {
      System.setProperty("sonarlint.home", sonarlintHome);
    } else {
      System.clearProperty("sonarlint.home");
    }
  }

  @Test
  public void fail_if_sonarlint_inst_home_not_defined() {
    Assume.assumeFalse(System.getProperty("sonarlint.home") != null);
    exception.expect(IllegalStateException.class);
    exception.expectMessage("The system property 'sonarlint.home' must be defined");
    assertThat(Utils.getSonarLintInstallationHome()).isNotNull();
  }

  @Test
  public void fail_if_invalid_path_for_analyzers() {
    exception.expect(IllegalStateException.class);
    exception.expectMessage("Failed to find analyzers");
    Utils.getAnalyzers(Paths.get("invalid"));
  }

  @Test
  public void getInstallationHome() {
    System.setProperty("sonarlint.home", "home");
    assertThat(Utils.getSonarLintInstallationHome()).isEqualTo(Paths.get("home"));
  }

  @Test
  public void fail_if_no_analyzers() throws IOException {
    Path plugins = temp.getRoot().toPath().resolve("plugins");
    Files.createDirectory(plugins);
    exception.expect(IllegalStateException.class);
    exception.expectMessage("Found no analyzers");
    Utils.getAnalyzers(plugins.getParent());
  }

  @Test
  public void find_analyzers() throws IOException {
    Path plugins = temp.getRoot().toPath().resolve("plugins");
    Files.createDirectory(plugins);
    Path jar = plugins.resolve("test.jar");
    Files.createFile(jar);
    assertThat(Utils.getAnalyzers(plugins.getParent())).contains(jar.toUri().toURL());
  }

}
