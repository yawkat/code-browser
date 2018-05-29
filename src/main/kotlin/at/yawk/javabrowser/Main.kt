package at.yawk.javabrowser

import java.nio.file.Files
import java.nio.file.Paths

/**
 * @author yawkat
 */
fun main(args: Array<String>) {
    val i = Paths.get("in")

    val printer = Printer()
    SourceFileParser(i).parse(printer)

    val o = Paths.get("out")
    printer.print(o)
}
