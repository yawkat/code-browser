package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactNode
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Equivalence
import com.google.common.html.HtmlEscapers
import freemarker.core.Environment
import freemarker.ext.util.WrapperTemplateModel
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateModel
import freemarker.template.TemplateModelException
import freemarker.template.TemplateScalarModel
import java.io.Writer

private fun escape(s: String) = HtmlEscapers.htmlEscaper().escape(s)

private fun splitPath(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val res = ArrayList<String>()
    var i = 0
    while (true) {
        val next = s.indexOf('/', i)
        if (next == -1) {
            if (i != s.length) {
                res.add(s.substring(i))
            }
            return res
        }
        res.add(s.substring(i, next + 1))
        i = next + 1
    }
}

class PathDirective : TemplateDirectiveModel {
    override fun execute(
        env: Environment,
        params: Map<Any?, Any?>,
        loopVars: Array<TemplateModel>,
        body: TemplateDirectiveBody?
    ) {
        val newPath = (params["newPath"] as? WrapperTemplateModel)?.wrappedObject as? ParsedPath
            ?: throw TemplateModelException("`newPath` must be a ParsedPath")
        val oldPath = params["oldPath"]?.let {
            if (it is TemplateScalarModel && it.asString.isEmpty()) null
            else (it as? WrapperTemplateModel)?.wrappedObject as? ParsedPath
                ?: throw TemplateModelException("`oldPath` must be a ParsedPath")
        }

        Impl(env.out).run(newPath, oldPath)
    }

    @VisibleForTesting
    internal class Impl(private val out: Writer) {
        fun run(newPath: ParsedPath, oldPath: ParsedPath?) {
            if (oldPath == null || newPath.artifact == oldPath.artifact) {
                // no diff in the artifact.
                printArtifact(newPath.artifact)
            } else if (
                newPath.artifact.parent == oldPath.artifact.parent &&
                newPath.artifact.parent != null &&
                newPath.artifact.children.isEmpty() &&
                oldPath.artifact.children.isEmpty()
            ) {
                // version difference between two leaf artifacts
                printArtifact(newPath.artifact.parent)
                val location = Locations.diffPath(
                    '/' + newPath.artifact.stringId,
                    '/' + oldPath.artifact.stringId
                )
                out.write("<a href='${escape(location)}'>")
                inlineDiff(
                    new = { out.write(escape(newPath.artifact.idInParent!!) + "/") },
                    old = { out.write(escape(oldPath.artifact.idInParent!!) + "/") }
                )
                out.write("</a> ")
            } else {
                // fallback
                inlineDiff(
                    new = { printArtifact(newPath.artifact) },
                    old = { printArtifact(oldPath.artifact) }
                )
            }

            require(oldPath == null || (newPath is ParsedPath.SourceFile) == (oldPath is ParsedPath.SourceFile))
            if (newPath is ParsedPath.SourceFile) {
                val splitNew = splitPath(newPath.sourceFilePath)
                val lastDirIndex =
                    if (newPath.sourceFilePath.endsWith("/")) splitNew.lastIndex
                    else splitNew.lastIndex - 1
                out.write("<span class='source-file-dir'>")
                if (oldPath == null) {
                    for ((i, part) in splitNew.withIndex()) {
                        val location = Locations.toPath(
                            ParsedPath.SourceFile(
                                newPath.artifact,
                                splitNew.subList(0, i + 1).joinToString("")
                            )
                        )
                        out.write("<a href='${escape(location)}'>${escape(part)}</a>")

                        if (lastDirIndex == i) {
                            out.write("</span>")
                        }
                    }
                } else {
                    require(oldPath is ParsedPath.SourceFile)
                    require(newPath.sourceFilePath.endsWith('/') == oldPath.sourceFilePath.endsWith('/')) {
                        "comparing dirs with files is weird"
                    }

                    val splitOld = splitPath(oldPath.sourceFilePath)
                    val diff = buildDiffEdits(
                        newItems = splitNew,
                        oldItems = splitOld,
                        equivalence = Equivalence.equals()
                    )

                    fun getDiffPathUntil(newI: Int, oldI: Int): String {
                        val partialNew = splitNew.subList(0, newI + 1).joinToString("")
                        val partialOld = splitOld.subList(0, oldI + 1).joinToString("")
                        return Locations.diffPath(
                            Locations.toPath(ParsedPath.SourceFile(newPath.artifact, partialNew)),
                            Locations.toPath(ParsedPath.SourceFile(oldPath.artifact, partialOld))
                        )
                    }

                    iterateEdits(
                        diff,
                        newLength = splitNew.size,
                        oldLength = splitOld.size,
                        visitUnchanged = { newR, oldR ->
                            for (i in newR) {
                                val location = getDiffPathUntil(i, i - newR.first + oldR.first)
                                val part = splitNew[i]
                                out.write("<a href='${escape(location)}'>${escape(part)}</a>")
                                if (lastDirIndex == i) {
                                    out.write("</span>")
                                }
                            }
                        },
                        visitChanged = { newR, oldR ->
                            if (lastDirIndex in newR) {
                                out.write("</span>")
                            }
                            val location = getDiffPathUntil(newR.last, oldR.last)
                            // if one of the paths has a new item at the front, we run into an issue. Technically, the
                            // link would be to a comparison to the base, e.g. java/11/java.base/ vs java/8/, but then
                            // one of the paths is a SourceFile and the other a LeafArtifact. Can of worms that I don't
                            // want to open right now, so just don't link that comparison.
                            val hasEmptyPath = newR.last == -1 || oldR.last == -1
                            if (!hasEmptyPath) {
                                out.write("<a href='${escape(location)}'>")
                            }
                            inlineDiff(
                                new = {
                                    if (!newR.isEmpty()) {
                                        out.write(escape(splitNew.subList(newR.first, newR.last + 1).joinToString("")))
                                    }
                                },
                                old = {
                                    if (!oldR.isEmpty()) {
                                        out.write(escape(splitNew.subList(newR.first, newR.last + 1).joinToString("")))
                                    }
                                }
                            )
                            if (!hasEmptyPath) {
                                out.write("</a>")
                            }
                        }
                    )
                }
            }
        }

        private inline fun inlineDiff(
            new: () -> Unit,
            old: () -> Unit
        ) {
            // 200B is a zero-width space. It makes the span take up vertical space even when the content is empty
            out.write("<span class='path-diff'><span class='foreground-new'>\u200B")
            new()
            out.write("</span><span class='foreground-old'>\u200B")
            old()
            out.write("</span></span>")
        }

        private fun printArtifact(artifact: ArtifactNode) {
            artifact.parent?.let {
                printArtifact(it)
            }
            printArtifactSingle(artifact)
        }

        private fun printArtifactSingle(artifact: ArtifactNode) {
            if (artifact.idInParent != null) {
                out.write("<a href='/${escape(artifact.stringId)}'>${escape(artifact.idInParent)}/</a> ")
            }
        }
    }
}