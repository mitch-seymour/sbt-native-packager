enablePlugins(JavaAppPackaging, ScriptPerMainClass)

name := "simple-app"
version := "0.1.0"

mainClass in Compile := Some("com.example.MainApp")

TaskKey[Unit]("run-check") := {
  val cwd = (stagingDirectory in Universal).value

  // check MainApp
  val cmd = Seq((cwd / "bin" / executableScriptName.value).getAbsolutePath)
  val output = Process(cmd, cwd).!!
  assert(output contains "MainApp", "Output didn't contain 'MainApp': " + output)

  // check MainApp additional script
  val cmdMain = Seq((cwd / "bin" / "main-app").getAbsolutePath)
  val outputMain = Process(cmdMain, cwd).!!
  assert(outputMain contains "MainApp", "Output didn't contain 'MainApp': " + outputMain)

  // check SecondApp
  val cmdSecond = Seq((cwd / "bin" / "second-app").getAbsolutePath)
  val outputSecond = Process(cmdSecond, cwd).!!
  assert(outputSecond contains "SecondApp", "Output didn't contain 'SecondApp': " + outputSecond)
}
