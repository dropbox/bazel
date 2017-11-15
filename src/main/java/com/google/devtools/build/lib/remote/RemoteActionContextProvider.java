// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.exec.ActionContextProvider;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.exec.apple.XCodeLocalEnvProvider;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalSpawnRunner;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.LinuxSandboxedStrategy;
import com.google.devtools.build.lib.sandbox.SandboxActionContextProvider;
import com.google.devtools.build.lib.sandbox.SandboxOptions;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.worker.WorkerActionContextProvider;
import com.google.devtools.build.lib.worker.WorkerModule;
import com.google.devtools.build.lib.worker.WorkerOptions;
import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Provide a remote execution context.
 */
final class RemoteActionContextProvider extends ActionContextProvider {
  private final CommandEnvironment env;
  private final RemoteActionCache cache;
  private final GrpcRemoteExecutor executor;

  RemoteActionContextProvider(CommandEnvironment env, @Nullable RemoteActionCache cache,
      @Nullable GrpcRemoteExecutor executor) {
    this.env = env;
    this.executor = executor;
    this.cache = cache;
  }

  @Override
  public Iterable<? extends ActionContext> getActionContexts() {
    ExecutionOptions executionOptions =
        checkNotNull(env.getOptions().getOptions(ExecutionOptions.class));
    RemoteOptions remoteOptions = checkNotNull(env.getOptions().getOptions(RemoteOptions.class));
    String buildRequestId = env.getBuildRequestId().toString();
    String commandId = env.getCommandId().toString();

    if (remoteOptions.experimentalRemoteSpawnCache || remoteOptions.experimentalLocalDiskCache) {
      RemoteSpawnCache spawnCache =
          new RemoteSpawnCache(
              env.getExecRoot(),
              remoteOptions,
              cache,
              buildRequestId,
              commandId,
              executionOptions.verboseFailures,
              env.getReporter());
      return ImmutableList.of(spawnCache);
    } else {
      RemoteSpawnRunner spawnRunner =
          new RemoteSpawnRunner(
              env.getExecRoot(),
              remoteOptions,
              createFallbackRunner(env),
              executionOptions.verboseFailures,
              env.getReporter(),
              buildRequestId,
              commandId,
              cache,
              executor);

      WorkerModule workerModule = null;
      for (BlazeModule mod : env.getRuntime().getBlazeModules()) {
        if (mod instanceof WorkerModule) {
          workerModule = (WorkerModule)mod;
        }
      }
      checkNotNull(workerModule);
      SpawnRunner spawnWrkRunner =
          new RemoteSpawnRunner(
              env.getExecRoot(),
              remoteOptions,
              WorkerActionContextProvider.createWorkerRunner(env, workerModule.ensureWorkers(env.getOptions().getOptions(WorkerOptions.class))),
              executionOptions.verboseFailures,
              env.getReporter(),
              buildRequestId,
              commandId,
              cache,
              executor);
      RemoteWorkerSpawnStrategy remoteWorkerStrategy = new RemoteWorkerSpawnStrategy(spawnWrkRunner);

      return ImmutableList.of(new RemoteSpawnStrategy(spawnRunner), remoteWorkerStrategy);
    }
  }

  private static SpawnRunner createFallbackRunner(CommandEnvironment env) {
    // DBX: This is all crappily cargo-culted from SandboxModule.
    BlazeDirectories blazeDirs = env.getDirectories();
    String productName = env.getRuntime().getProductName();
    SandboxOptions sandboxOptions = env.getOptions().getOptions(SandboxOptions.class);
    FileSystem fs = env.getRuntime().getFileSystem();

    Path sandboxBase;
    if (sandboxOptions.sandboxBase.isEmpty()) {
      sandboxBase = blazeDirs.getOutputBase().getRelative(productName + "-sandbox");
    } else {
      String dirName =
          productName + "-sandbox." + Fingerprint.md5Digest(blazeDirs.getOutputBase().toString());
      sandboxBase = fs.getPath(sandboxOptions.sandboxBase).getRelative(dirName);
    }
    int timeoutGraceSeconds =
        env.getOptions().getOptions(LocalExecutionOptions.class).localSigkillGraceSeconds;
    try {
      return SandboxActionContextProvider.withFallback(env, LinuxSandboxedStrategy.create(env, sandboxBase, productName, timeoutGraceSeconds));
    } catch (IOException e) {}

    LocalExecutionOptions localExecutionOptions =
        env.getOptions().getOptions(LocalExecutionOptions.class);
    LocalEnvProvider localEnvProvider = OS.getCurrent() == OS.DARWIN
        ? new XCodeLocalEnvProvider()
        : LocalEnvProvider.UNMODIFIED;
    return
        new LocalSpawnRunner(
            env.getExecRoot(),
            localExecutionOptions,
            ResourceManager.instance(),
            env.getRuntime().getProductName(),
            localEnvProvider);
  }

  @Override
  public void executionPhaseEnding() {
    if (cache != null) {
      cache.close();
    }
  }
}
