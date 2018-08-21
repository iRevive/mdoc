package mdoc.website

import java.nio.file.Paths
import scala.meta.internal.io.PathIO
import mdoc.Main
import mdoc.MainSettings
import mdoc.internal.BuildInfo

object Website {
  def main(args: Array[String]): Unit = {
    val cwd = PathIO.workingDirectory.toNIO
    val settings = MainSettings()
      .withIn(Paths.get("docs"))
      .withOut(cwd)
      .withSiteVariables(Map("VERSION" -> BuildInfo.stableVersion))
      .withCleanTarget(false)
      .withArgs(args.toList)
      .withReportRelativePaths(true)
      .withWatch(true)
    val context = settings.settings.validate(settings.reporter).get
    val stringModifier = new MdocModifier(context)
    val exitCode = Main.process(settings.withStringModifiers(List(stringModifier)))
    sys.exit(exitCode)
  }
}