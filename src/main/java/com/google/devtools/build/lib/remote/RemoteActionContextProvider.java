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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.devtools.build.lib.actions.ActionContextProvider;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.Executor.ActionContext;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.SandboxModule;

import java.util.ArrayList;

/**
 * Provide a remote execution context.
 */
final class RemoteActionContextProvider extends ActionContextProvider {
  private final ImmutableList<ActionContext> strategies;

  RemoteActionContextProvider(
      CommandEnvironment env,
      BuildRequest buildRequest,
      RemoteActionCache actionCache,
      RemoteWorkExecutor workExecutor) {
    boolean verboseFailures = buildRequest.getOptions(ExecutionOptions.class).verboseFailures;

    SpawnActionContext sandboxStrategy = null;
    SandboxModule sandboxModule = env.getRuntime().getBlazeModule(SandboxModule.class);
    for (ActionContextProvider acp: sandboxModule.getActionContextProviders()) {
        for (ActionContext ac: acp.getActionContexts()) {
          sandboxStrategy = (SpawnActionContext)ac;
        }
    }

    Builder<ActionContext> strategiesBuilder = ImmutableList.builder();
    strategiesBuilder.add(
        new RemoteSpawnStrategy(
            env.getClientEnv(),
            env.getExecRoot(),
            buildRequest.getOptions(RemoteOptions.class),
            verboseFailures,
            actionCache,
            workExecutor,
            env.getRuntime().getProductName(),
            sandboxStrategy));
    this.strategies = strategiesBuilder.build();
  }

  @Override
  public Iterable<ActionContext> getActionContexts() {
    return strategies;
  }
}
