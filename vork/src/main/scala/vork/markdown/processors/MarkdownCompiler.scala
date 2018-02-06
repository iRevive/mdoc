package vork.markdown.processors

import scalafix._
import scala.meta._
import java.io.File
import java.net.URLClassLoader
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter
import metaconfig.ConfError
import metaconfig.Configured
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import vork.runtime.Document
import vork.runtime.DocumentBuilder
import vork.runtime.Section

object MarkdownCompiler {
  def default(): MarkdownCompiler = new MarkdownCompiler(defaultClasspath)
  def render(sections: List[String], compiler: MarkdownCompiler): String = {
    val sources = sections.map(s => dialects.Sbt1(s).parse[Source].get)
    val instrumented = MarkdownCompiler.instrumentSections(sources)
    val doc = MarkdownCompiler.document(compiler, instrumented)
    val obtained = renderDocument(doc, sources.map(_.stats))
    obtained
  }

  def renderDocument(doc: Document, statements: List[List[Tree]]): String = {
    val sb = new StringBuilder

    def section(s: Section, trees: List[Tree]): Unit = {
      sb.append("```scala\n")
      var first = true
      s.statements.zip(trees).foreach {
        case (statement, tree) =>
          if (first) {
            first = false
          } else {
            sb.append("\n")
          }
          sb.append("@ ")
            .append(tree.syntax)

          if (statement.out.nonEmpty) {
            sb.append("\n").append(statement.out)
          }
          if (sb.charAt(sb.size - 1) != '\n') {
            sb.append("\n")
          }
          statement.binders.foreach { binder =>
            sb.append(binder.name)
              .append(": ")
              .append(binder.tpe.render)
              .append(" = ")
              .append(pprint.PPrinter.BlackWhite.apply(binder.value))
              .append("\n")
          }
      }
      sb.append("```\n\n")
    }
    doc.sections.zip(statements).foreach {
      case (a, b) => section(a, b)
    }
    sb.toString()
  }

  private val counter = new AtomicInteger()
  def document(compiler: MarkdownCompiler, instrumented: String): Document = {
    val name = "Generated" + counter.getAndIncrement()
    val wrapped =
      s"""
         |package vork
         |class $name extends _root_.vork.runtime.DocumentBuilder {
         |  def app(): Unit = {
         |    $instrumented
         |  }
         |}
      """.stripMargin
    val loader = compiler.compile(Input.String(wrapped)).get
    val cls = loader.loadClass(s"vork.$name")
    cls.newInstance().asInstanceOf[DocumentBuilder].build()
  }

  // Copy paste from scalafix
  def defaultClasspath: String = {
    getClass.getClassLoader match {
      case u: URLClassLoader =>
        val paths = u.getURLs.toList.map(u => {
          if (u.getProtocol.startsWith("bootstrap")) {
            import java.io._
            import java.nio.file._
            val stream = u.openStream
            val tmp = File.createTempFile("bootstrap-" + u.getPath, ".jar")
            Files.copy(stream, Paths.get(tmp.getAbsolutePath), StandardCopyOption.REPLACE_EXISTING)
            tmp.getAbsolutePath
          } else {
            URLDecoder.decode(u.getPath, "UTF-8")
          }
        })
        paths.mkString(File.pathSeparator)
      case _ => ""
    }
  }
  def instrumentSections(sections: List[Source]): String = {
    val stats = sections.map(instrument)
    val out = stats.foldRight("") {
      case (section, "") =>
        s"$section; section { () }"
      case (section, accum) =>
        s"$section; section { $accum }"
    }
    out
  }

  object Binders {
    def binders(pat: Pat): List[Name] =
      pat.collect { case m: Member => m.name }
    def unapply(tree: Tree): Option[List[Name]] = tree match {
      case Defn.Val(_, pats, _, _) => Some(pats.flatMap(binders))
      case Defn.Var(_, pats, _, _) => Some(pats.flatMap(binders))
      case _ => None
    }
  }

  def instrument(source: Source): String = {
    val stats = source.stats
    val ctx = RuleCtx(source)
    val rule = Rule.syntactic("Vork") { ctx =>
      val last = ctx.tokens.last
      val patches = stats.map {
        case stat @ Binders(names) =>
          val binders = names
            .map(name => s"binder($name)")
            .mkString(";", ";", "; statement {")
          ctx.addRight(stat, binders) +
            ctx.addRight(last, " }")
        case _ => Patch.empty
      }
      val patch = patches.asPatch
      patch
    }
    rule.apply(ctx)
  }
}

class MarkdownCompiler(
    classpath: String,
    target: AbstractFile = new VirtualDirectory("(memory)", None)
) {
  private val settings = new Settings()
  settings.deprecation.value = true // enable detailed deprecation warnings
  settings.unchecked.value = true // enable detailed unchecked warnings
  settings.outputDirs.setSingleOutput(target)
  settings.classpath.value = classpath
  lazy val reporter = new StoreReporter
  private val global = new Global(settings, reporter)
  private val classLoader =
    new AbstractFileClassLoader(target, this.getClass.getClassLoader)

  def compile(input: Input): Configured[ClassLoader] = {
    reporter.reset()
    val run = new global.Run
    val label = input match {
      case Input.File(path, _) => path.toString()
      case Input.VirtualFile(path, _) => path
      case _ => "(input)"
    }
    run.compileSources(List(new BatchSourceFile(label, new String(input.chars))))
    val errors = reporter.infos.collect {
      case reporter.Info(pos, msg, reporter.ERROR) =>
        ConfError
          .message(msg)
          .atPos(
            if (pos.isDefined) Position.Range(input, pos.start, pos.end)
            else Position.None
          )
          .notOk
    }
    ConfError
      .fromResults(errors.toSeq)
      .map(_.notOk)
      .getOrElse(Configured.Ok(classLoader))
  }
}
