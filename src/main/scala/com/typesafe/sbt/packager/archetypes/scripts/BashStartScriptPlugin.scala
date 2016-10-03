package com.typesafe.sbt.packager.archetypes.scripts

import java.io.File
import java.net.URL

import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging.autoImport.{
  bashScriptEnvConfigLocation => _,
  scriptClasspath => _,
  _
}
import com.typesafe.sbt.packager.archetypes.{JavaAppPackaging, TemplateWriter}
import sbt.Keys._
import sbt._

/**
  * == Bash StartScript Plugin ==
  *
  * This plugins creates a start bash script to run an application built with the
  * [[com.typesafe.sbt.packager.archetypes.JavaAppPackaging]].
  *
  */
object BashStartScriptPlugin extends AutoPlugin {

  /**
    * Name of the bash template if user wants to provide custom one
    */
  val bashTemplate = "bash-template"

  /**
    * Location for the application.ini file used by the bash script to load initialization parameters for jvm and app
    */
  val appIniLocation = "${app_home}/../conf/application.ini"

  /**
    * Script destination in final package
    */
  val scriptTargetFolder = "bin"

  override val requires = JavaAppPackaging
  override val trigger = AllRequirements

  object autoImport extends BashStartScriptKeys

  private case class BashScriptConfig(executableScriptName: String,
                                      scriptClasspath: Seq[String],
                                      bashDefines: Seq[String],
                                      bashScriptTemplateLocation: File)

  override def projectSettings: Seq[Setting[_]] = Seq(
    bashScriptTemplateLocation := (sourceDirectory.value / "templates" / bashTemplate),
    bashScriptExtraDefines := Nil,
    bashScriptDefines := Defines((scriptClasspath in bashScriptDefines).value, bashScriptConfigLocation.value),
    bashScriptDefines ++= bashScriptExtraDefines.value,
    // Create a bashConfigLocation if options are set in build.sbt
    bashScriptConfigLocation <<= bashScriptConfigLocation ?? Some(appIniLocation),
    bashScriptEnvConfigLocation <<= bashScriptEnvConfigLocation ?? None,
    // Generating the application configuration
    mappings in Universal := {
      val log = streams.value.log
      val universalMappings = (mappings in Universal).value
      val dir = (target in Universal).value
      val options = (javaOptions in Universal).value

      bashScriptConfigLocation.value.collect {
        case location if options.nonEmpty =>
          val configFile = dir / "tmp" / "conf" / "application.ini"
          //Do not use writeLines here because of issue #637
          IO.write(configFile, ("# options from build" +: options).mkString("\n"))
          val filteredMappings = universalMappings.filter {
            case (file, path) => path != appIniLocation
          }
          // Warn the user if he tries to specify options
          if (filteredMappings.size < universalMappings.size) {
            log.warn("--------!!! JVM Options are defined twice !!!-----------")
            log.warn(
              "application.ini is already present in output package. Will be overriden by 'javaOptions in Universal'"
            )
          }
          (configFile -> cleanApplicationIniPath(location)) +: filteredMappings

      }.getOrElse(universalMappings)
    },
    makeBashScripts := {
      val tmp = (target in Universal).value / "scripts"
      val classpath = (scriptClasspath in bashScriptDefines).value

      val config = BashScriptConfig(
        executableScriptName = executableScriptName.value,
        scriptClasspath = classpath,
        bashDefines = bashScriptDefines.value ++ bashScriptExtraDefines.value,
        bashScriptTemplateLocation = bashScriptTemplateLocation.value
      )

      generateStartScripts(
        config,
        (mainClass in Compile).value,
        (discoveredMainClasses in Compile).value,
        tmp,
        streams.value.log
      )
    },
    mappings in Universal ++= makeBashScripts.value
  )

  private[this] def generateStartScripts(config: BashScriptConfig,
                                         mainClass: Option[String],
                                         discoveredMainClasses: Seq[String],
                                         targetDir: File,
                                         log: Logger): Seq[(File, String)] =
    mainClass match {
      // only one main - create the default script
      case Some(main) if discoveredMainClasses.size == 1 =>
        log.info("Create single bash start script")
        Seq(MainScript(main, config, targetDir) -> s"$scriptTargetFolder/${config.executableScriptName}")
      // main explicitly set and multiple discoveredMainClasses
      case Some(main) =>
        log.info(s"Create main script for $main and forwarder scripts")
        val mainScript = MainScript(main, config, targetDir) -> s"$scriptTargetFolder/${config.executableScriptName}"
        mainScript +: ForwarderScripts(
          config.executableScriptName,
          discoveredMainClasses.filterNot(_ == main),
          targetDir
        )
      // no main class at all
      case None if discoveredMainClasses.isEmpty =>
        log.warn("You have no main class in your project. No start script will be generated.")
        Seq.empty
      // multiple main classes and none explicitly set. Create start script for each class
      case None =>
        log.info("Create bash start scripts for all main classes")
        generateMainScripts(discoveredMainClasses, config, targetDir)

    }

  private[this] def generateMainScripts(discoveredMainClasses: Seq[String],
                                        config: BashScriptConfig,
                                        targetDir: File): Seq[(File, String)] =
    discoveredMainClasses.map { qualifiedClassName =>
      val bashConfig =
        config.copy(executableScriptName = makeScriptName(qualifiedClassName))
      MainScript(qualifiedClassName, bashConfig, targetDir) -> s"$scriptTargetFolder/${bashConfig.executableScriptName}"
    }

  private[this] def makeScriptName(qualifiedClassName: String): String = {
    val clazz = qualifiedClassName.split("\\.").last

    val lowerCased = clazz.drop(1).flatMap {
      case c if c.isUpper => Seq('-', c.toLower)
      case c => Seq(c)
    }

    clazz(0).toLower +: lowerCased
  }

  /**
    * @param path that could be relative to app_home
    * @return path relative to app_home
    */
  private def cleanApplicationIniPath(path: String): String =
    path.replaceFirst("\\$\\{app_home\\}/../", "")

  /**
    * Bash defines
    */
  object Defines {

    /**
      * Creates the block of defines for a script.
      *
      * @param appClasspath A sequence of relative-locations (to the lib/ folder) of jars
      *                     to include on the classpath.
      * @param configFile An (optional) filename from which the script will read arguments.
      */
    def apply(appClasspath: Seq[String], configFile: Option[String]): Seq[String] =
      (configFile map configFileDefine).toSeq ++ Seq(makeClasspathDefine(appClasspath))

    def mainClass(mainClass: String) = {
      val jarPrefixed = """^\-jar (.*)""".r
      val args = mainClass match {
        case jarPrefixed(jarName) => Seq("-jar", jarName)
        case className => Seq(className)
      }
      val quotedArgsSpaceSeparated =
        args.map(s => "\"" + s + "\"").mkString(" ")
      "declare -a app_mainclass=(%s)\n" format quotedArgsSpaceSeparated
    }

    private[this] def makeClasspathDefine(cp: Seq[String]): String = {
      val fullString = cp map (n =>
                                 if (n.startsWith(File.separator)) n
                                 else "$lib_dir/" + n) mkString ":"
      "declare -r app_classpath=\"" + fullString + "\"\n"
    }

    private[this] def configFileDefine(configFile: String) =
      "declare -r script_conf_file=\"%s\"" format configFile
  }

  object MainScript {

    /**
      *
      * @param mainClass - Main class added to the java command
      * @param config - Config data for this script
      * @param targetDir - Target directory for this script
      * @return File pointing to the created main script
      */
    def apply(mainClass: String, config: BashScriptConfig, targetDir: File): File = {
      val template = resolveTemplate(config.bashScriptTemplateLocation)
      val defines = Defines.mainClass(mainClass) +: config.bashDefines
      val scriptContent = generateScript(defines, template)
      val script = targetDir / "scripts" / config.executableScriptName
      IO.write(script, scriptContent)
      // TODO - Better control over this!
      script.setExecutable(true)
      script
    }

    private[this] def resolveTemplate(defaultTemplateLocation: File): URL =
      if (defaultTemplateLocation.exists) defaultTemplateLocation.toURI.toURL
      else getClass.getResource(defaultTemplateLocation.getName)

    private[this] def generateScript(defines: Seq[String], template: URL): String = {
      val defineString = defines mkString "\n"
      val replacements = Seq("template_declares" -> defineString)
      TemplateWriter.generateScript(template, replacements)
    }
  }

  object ForwarderScripts {
    def apply(executableScriptName: String, discoveredMainClasses: Seq[String], targetDir: File): Seq[(File, String)] = {
      val tmp = targetDir / "scripts"
      discoveredMainClasses.map { qualifiedClassName =>
        val clazz = makeScriptName(qualifiedClassName)
        val file = tmp / clazz
        IO.write(file, forwarderScript(executableScriptName, qualifiedClassName))
        file.setExecutable(true)
        file -> s"bin/$clazz"
      }
    }

    private[this] def forwarderScript(startScript: String, qualifiedClassName: String): String =
      s"""|#!/bin/sh
	  |# Absolute path to this script
	  |SCRIPT=$$(readlink -f "$$0")
	  |SCRIPTPATH=$$(dirname "$$SCRIPT")
	  |$$SCRIPTPATH/$startScript -main $qualifiedClassName "$$@"
	  |""".stripMargin
  }
}
