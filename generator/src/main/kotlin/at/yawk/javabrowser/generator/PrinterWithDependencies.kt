package at.yawk.javabrowser.generator

/**
 * @author yawkat
 */
interface PrinterWithDependencies : Printer {
    fun addDependency(dependency: String)
}