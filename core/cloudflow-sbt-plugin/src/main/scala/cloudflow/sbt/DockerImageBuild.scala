/*
 * Copyright (C) 2016-2026 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.sbt

import scala.sys.process.{ Process, ProcessLogger }

import sbt._
import sbtdocker._
import sbtdocker.staging.DefaultDockerfileProcessor

/** Builds an image the way sbt-docker's `docker` task does, except for how it learns the image id.
  *
  * sbt-docker (1.9.0 to 1.11.0) finds the id by parsing the builder's console output, then tags the image by that id.
  * On Docker's containerd image store — the default for new Docker 29 installs — BuildKit prints output that 1.9.0 does
  * not recognise at all, and from which 1.11.0 takes the config digest, which that store does not know as an image.
  * Either way `buildApp` fails.
  *
  * Here the names are applied by `docker build -t` itself, and the id is read from the file `--iidfile` writes: no
  * output parsing, and no separate tag step that needs the id to be right. Both flags are supported by the legacy
  * builder, BuildKit and Podman.
  */
object DockerImageBuild {

  def apply(
      dockerfile: DockerfileBase,
      imageNames: Seq[ImageName],
      buildOptions: BuildOptions,
      buildArguments: Map[String, String],
      stageDir: File,
      dockerPath: String,
      log: Logger): ImageId = {
    val dockerfilePath = dockerfile match {
      case NativeDockerfile(path) => path
      case dockerfileLike: DockerfileLike =>
        val staged = DefaultDockerfileProcessor(dockerfileLike, stageDir)
        log.debug("Building Dockerfile:\n" + staged.instructionsString)
        IO.delete(stageDir)
        val path = stageDir / "Dockerfile"
        IO.write(path, staged.instructionsString)
        staged.stageFiles.foreach { case (source, destination) => source.stage(destination) }
        path
    }
    build(dockerfilePath.getAbsoluteFile, imageNames, buildOptions, buildArguments, dockerPath, log)
  }

  private def build(
      dockerfilePath: File,
      imageNames: Seq[ImageName],
      buildOptions: BuildOptions,
      buildArguments: Map[String, String],
      dockerPath: String,
      log: Logger): ImageId = {
    val contextDir = dockerfilePath.getParentFile
    val iidFile = IO.createTemporaryDirectory / "iid"
    var lines = Vector.empty[String]

    def run(withProgress: Boolean): Int = {
      val command =
        Seq(dockerPath, "build") ++
          optionFlags(buildOptions) ++
          (if (withProgress) Seq("--progress=plain") else Nil) ++
          buildArguments.toSeq.flatMap { case (key, value) => Seq("--build-arg", s"$key=$value") } ++
          imageNames.flatMap(name => Seq("--tag", name.toString)) ++
          Seq("--iidfile", iidFile.getAbsolutePath, "--file", dockerfilePath.name, contextDir.getPath)
      log.debug(s"Running command: '${command.mkString(" ")}'")
      val collect: String => Unit = { line =>
        log.info(line)
        lines :+= line
      }
      Process(command, contextDir).!(ProcessLogger(collect, collect))
    }

    try {
      val first = run(withProgress = true)
      // As sbt-docker does: Docker versions without BuildKit reject --progress.
      val exitCode =
        if (first != 0 && lines.exists(_.contains("unknown flag: --progress"))) run(withProgress = false) else first
      if (exitCode != 0) throw new DockerBuildException(s"Failed to build Docker image (exit code: $exitCode)")
      readImageId(iidFile)
    } finally IO.delete(iidFile.getParentFile)
  }

  /** The iidfile holds `sha256:<hex>`; sbt-docker's `ImageId` is the bare hex, as `docker images` prints it. */
  private[sbt] def readImageId(iidFile: File): ImageId = {
    val content = if (iidFile.exists) IO.read(iidFile).trim else ""
    if (content.isEmpty)
      throw new DockerBuildException(s"The build succeeded but wrote no image id to $iidFile")
    ImageId(content.stripPrefix("sha256:"))
  }

  /** The same flags sbt-docker derives from `BuildOptions`; its own function is package-private. */
  private def optionFlags(options: BuildOptions): Seq[String] = {
    val remove = options.removeIntermediateContainers match {
      case BuildOptions.Remove.Always    => "--force-rm=true"
      case BuildOptions.Remove.Never     => "--rm=false"
      case BuildOptions.Remove.OnSuccess => "--rm=true"
    }
    val pull = options.pullBaseImage match {
      case BuildOptions.Pull.Always    => "--pull=true"
      case BuildOptions.Pull.IfMissing => "--pull=false"
    }
    Seq(s"--no-cache=${!options.cache}", remove, pull) ++ options.additionalArguments
  }
}
