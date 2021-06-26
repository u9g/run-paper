/*
 * Run Paper Gradle Plugin
 * Copyright (c) 2021 Jason Penilla
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
package xyz.jpenilla.runpaper.task

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildServiceRegistration
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.property
import xyz.jpenilla.runpaper.Constants
import xyz.jpenilla.runpaper.service.PaperclipService
import java.io.File

/**
 * Task to download and run a Paper server along with plugins.
 */
@Suppress("unused")
public abstract class RunServerTask : JavaExec() {
  private val minecraftVersion: Property<String> = this.project.objects.property()
  private val paperBuild: Property<PaperBuild> = this.project.objects.property<PaperBuild>().convention(PaperBuild.Latest)
  private val paperclipService: Provider<PaperclipService> = this.project.gradle.sharedServices.registrations
    .named<BuildServiceRegistration<PaperclipService, PaperclipService.Parameters>>(Constants.Services.PAPERCLIP).flatMap { it.service }

  /**
   * Setting this property allows configuring a custom jar file to start the
   * server from. If left un-configured, Run Paper will resolve a Paperclip
   * using the Paper downloads API.
   */
  @get:Optional
  @get:InputFile
  public abstract val serverJar: RegularFileProperty

  /**
   * Run Paper makes use of Paper's `add-plugin` command line option in order to
   * load the files in [pluginJars] as plugins. This option was implemented during
   * the Minecraft 1.16.5 development cycle, and does not exist in prior versions.
   *
   * Enabling legacy plugin loading instructs Run Paper to copy jars into the plugins
   * folder instead of using the aforementioned command line option, for better
   * compatibility with legacy Minecraft versions.
   *
   * If left un-configured, Run Paper will attempt to automatically
   * determine the appropriate setting based on the configured
   * Minecraft version for this task.
   */
  @get:Optional
  @get:Input
  public abstract val legacyPluginLoading: Property<Boolean>

  /**
   * The run directory for the test server.
   * Defaults to `run` in the project directory.
   */
  @Internal
  public val runDirectory: DirectoryProperty = this.project.objects.directoryProperty().convention(this.project.layout.projectDirectory.dir("run"))

  /**
   * The collection of plugin jars to load. Run Paper will attempt to locate
   * a plugin jar from the shadowJar task output if present, or else the standard
   * jar archive. In non-standard setups, it may be necessary to manually add
   * your plugin's jar to this collection, as well as specify task dependencies.
   *
   * Adding files to this collection may also be useful for projects which produce
   * more than one plugin jar, or to load dependency plugins.
   */
  @get:InputFiles
  public abstract val pluginJars: ConfigurableFileCollection

  override fun exec() {
    this.configure()
    this.beforeExec()
    this.logger.lifecycle("Starting Paper...")
    this.logger.lifecycle("")
    super.exec()
  }

  private fun configure() {
    if (!this.minecraftVersion.isPresent) {
      error("No Minecraft version was specified for the '${this.name}' task!")
    }

    this.standardInput = System.`in`
    this.workingDir(this.runDirectory)

    val paperclip = if (this.serverJar.isPresent) {
      this.serverJar.get().asFile
    } else {
      this.paperclipService.get().resolvePaperclip(
        this.project,
        this.minecraftVersion.get(),
        this.paperBuild.get()
      )
    }
    this.classpath(paperclip)

    // Set disable watchdog property for debugging
    this.systemProperty("disable.watchdog", true)

    this.systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", true)

    // Add our arguments
    if (this.minecraftVersionIsSameOrNewerThan(1, 15)) {
      this.args("--nogui")
    }
  }

  private fun beforeExec() {
    // Create working dir if needed
    val workingDir = this.runDirectory.get().asFile
    if (!workingDir.exists()) {
      workingDir.mkdirs()
    }

    val plugins = workingDir.resolve("plugins")
    if (!plugins.exists()) {
      plugins.mkdirs()
    }

    val prefix = "_run-paper_plugin_"
    val extension = ".jar"

    // Delete any jars left over from previous legacy mode runs
    plugins.listFiles()
      ?.filter { it.isFile }
      ?.filter { it.name.startsWith(prefix) && it.name.endsWith(extension) }
      ?.forEach { it.delete() }

    // Add plugins
    if (this.addPluginArgumentSupported()) {
      this.args(this.pluginJars.files.map { "-add-plugin=${it.absolutePath}" })
    } else {
      this.pluginJars.files.forEachIndexed { i, jar ->
        jar.copyTo(plugins.resolve(prefix + i + extension))
      }
    }
  }

  private fun addPluginArgumentSupported(): Boolean {
    if (this.legacyPluginLoading.isPresent) {
      return !this.legacyPluginLoading.get()
    }

    return this.minecraftVersionIsSameOrNewerThan(1, 16, 5)
  }

  private fun minecraftVersionIsSameOrNewerThan(vararg other: Int): Boolean {
    val minecraft = this.minecraftVersion.get().split(".").map {
      try {
        it.toInt()
      } catch (ex: NumberFormatException) {
        return true
      }
    }

    for ((current, target) in minecraft zip other.toList()) {
      if (current < target) return false
      if (current > target) return true
      // If equal, check next subversion
    }

    // version is same
    return true
  }

  /**
   * Sets the Minecraft version to use.
   *
   * @param minecraftVersion minecraft version
   */
  public fun minecraftVersion(minecraftVersion: String) {
    this.minecraftVersion.set(minecraftVersion)
  }

  /**
   * Sets the build of Paper to use. By default, [PaperBuild.Latest] is
   * used, which uses the latest build for the configured Minecraft version.
   *
   * @param paperBuild paper build
   */
  public fun paperBuild(paperBuild: PaperBuild) {
    this.paperBuild.set(paperBuild)
  }

  /**
   * Sets a specific build number of Paper to use. By default the latest
   * build for the configured Minecraft version is used.
   *
   * @param paperBuildNumber build number
   */
  public fun paperBuild(paperBuildNumber: Int) {
    this.paperBuild.set(PaperBuild.Specific(paperBuildNumber))
  }

  /**
   * Sets the run directory for the test server.
   * Defaults to `run` in the project directory.
   *
   * @param runDirectory run directory
   */
  public fun runDirectory(runDirectory: File) {
    this.runDirectory.set(runDirectory)
  }

  /**
   * Convenience method for configuring the [serverJar] property.
   *
   * @param file server jar file
   */
  @Deprecated("Replaced by serverJar.", replaceWith = ReplaceWith("serverJar(file)"))
  public fun paperclip(file: File) {
    this.serverJar.set(file)
  }

  /**
   * Convenience method for configuring the [serverJar] property.
   *
   * @param file server jar file provider
   */
  @Deprecated("Replaced by serverJar.", replaceWith = ReplaceWith("serverJar(file)"))
  public fun paperclip(file: Provider<RegularFile>) {
    this.serverJar.set(file)
  }

  /**
   * Convenience method for configuring the [serverJar] property.
   *
   * @param file server jar file
   */
  public fun serverJar(file: File) {
    this.serverJar.set(file)
  }

  /**
   * Convenience method for configuring the [serverJar] property.
   *
   * @param file server jar file provider
   */
  public fun serverJar(file: Provider<RegularFile>) {
    this.serverJar.set(file)
  }

  /**
   * Convenience method for easily adding jars to [pluginJars].
   *
   * @param jars jars to add
   */
  public fun pluginJars(vararg jars: File) {
    this.pluginJars.from(jars)
  }

  /**
   * Convenience method for easily adding jars to [pluginJars].
   *
   * @param jars jars to add
   */
  public fun pluginJars(vararg jars: Provider<RegularFile>) {
    this.pluginJars.from(jars)
  }

  /**
   * Convenience method setting [legacyPluginLoading] to `true`.
   */
  public fun legacyPluginLoading() {
    this.legacyPluginLoading.set(true)
  }

  /**
   * Represents a build of Paper.
   */
  public sealed class PaperBuild {
    public companion object {
      /**
       * [PaperBuild] instance pointing to the latest Paper build for the configured Minecraft version.
       */
      @Deprecated("Replaced by RunServerTask.PaperBuild.Latest.", replaceWith = ReplaceWith("RunServerTask.PaperBuild.Latest"))
      public val LATEST: PaperBuild = Latest
    }

    /**
     * [PaperBuild] pointing to the latest Paper build for the configured Minecraft version.
     */
    public object Latest : PaperBuild()

    public data class Specific internal constructor(internal val buildNumber: Int) : PaperBuild()
  }

  internal fun resolvePluginJarTask(): AbstractArchiveTask? {
    if (this.project.plugins.hasPlugin(Constants.Plugins.SHADOW_PLUGIN_ID)) {
      return this.project.tasks.getByName<AbstractArchiveTask>(Constants.Plugins.SHADOW_JAR_TASK_NAME)
    }
    return this.project.tasks.findByName("jar") as? AbstractArchiveTask
  }
}
