/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Jdk9.CompileJdk9
import com.github.sbt.osgi.OsgiKeys
import io.github.roiocam.DependWalkerPlugin.autoImport.walkTasks
import io.github.roiocam.TaskDefine._
import io.github.roiocam._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyKeys

object PekkoDependWalker {

  lazy val jdk9CompileCheckSetting = Seq(
    walkTasks := Seq(
      WalkTask(
        ScopeKeyMatcher((Compile / packageBin).scopedKey, CheckBoth),
        ScopeKeyMatcher((CompileJdk9 / compile).scopedKey, CheckConfig)),
      WalkTask(
        ScopeKeyMatcher((Compile / fullClasspath).scopedKey, CheckBoth),
        ScopeKeyMatcher((CompileJdk9 / exportedProducts).scopedKey, CheckConfig)),
      WalkTask(
        ScopeKeyMatcher((Compile / packageBin).scopedKey, CheckBoth),
        ScopeKeyMatcher((CompileJdk9 / fullClasspath).scopedKey, CheckConfig)),
      WalkTask(
        ScopeKeyMatcher((Compile / packageBin).scopedKey, CheckBoth),
        ScopeKeyMatcher(OsgiKeys.bundle.scopedKey, CheckTask))))

  lazy val protobufV3Settings = Seq(
    walkTasks := Seq(
      WalkTask(
        ScopeKeyMatcher(OsgiKeys.bundle.scopedKey, CheckTask),
        ScopeKeyMatcher(AssemblyKeys.assembly.scopedKey, CheckTask))))

}
