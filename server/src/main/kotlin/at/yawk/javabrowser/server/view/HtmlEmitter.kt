package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.LocalVariableOrLabelRef
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.RenderedJavadoc
import at.yawk.javabrowser.SourceAnnotation
import at.yawk.javabrowser.SourceLineRef
import at.yawk.javabrowser.Style
import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.Escaper
import at.yawk.javabrowser.server.Locations
import at.yawk.javabrowser.server.SourceFilePrinter
import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.Writer
import java.net.URI
import java.util.*

class HtmlEmitter(
        private val bindingResolver: BindingResolver,
        private val scopes: Map<SourceFilePrinter.Scope, ScopeInfo>,
        private val writer: Writer,

        /**
         * Do we have an overlay to show super references?
         */
        private val hasOverlay: Boolean,
        /**
         * Can the current document be referenced and should we include element IDs for that purpose?
         */
        private val referenceThisUrl: Boolean,
        private val renderJavadoc: Boolean,
        /**
         * This source file's URI, or `null` if we're already on the uri for this source file.
         */
        ownUri: URI? = null
) : SourceFilePrinter.Emitter<HtmlEmitter.Memory> {
    private val ownUriString = ownUri?.toASCIIString() ?: ""

    sealed class Memory {
        object None : Memory()
        class ResolvedBinding(val uri: String?) : Memory()
        class Decl(
            val superBindingUris: List<String?>,
            val correspondingUris: Map<Realm, URI>
        ) : Memory()
    }

    data class ScopeInfo(
            val realm: Realm,
            val artifactId: String,
            val classpath: Set<String>
    )

    private val SourceFilePrinter.Scope.prefix: String
        get() = if (this == SourceFilePrinter.Scope.OLD) "--- " else ""

    override fun computeMemory(scope: SourceFilePrinter.Scope, annotation: SourceAnnotation): Memory {
        val scopeInfo = scopes.getValue(scope)
        return when (annotation) {
            is BindingRef -> Memory.ResolvedBinding(
                bindingResolver.resolveBinding(scopeInfo.realm, scopeInfo.classpath, annotation.binding).firstOrNull()
                    ?.toASCIIString()
            )
            is BindingDecl -> {
                val corresponding = EnumMap<Realm, URI>(Realm::class.java)
                for ((realm, bindingId) in annotation.corresponding) {
                    val uri = bindingResolver.resolveBinding(realm, setOf(scopeInfo.artifactId), bindingId)
                        .singleOrNull() ?: continue
                    corresponding[realm] = uri
                }
                Memory.Decl(
                    annotation.superBindings.map {
                        bindingResolver.resolveBinding(scopeInfo.realm, scopeInfo.classpath, it.binding).firstOrNull()
                            ?.toASCIIString()
                    },
                    corresponding
                )
            }
            else -> Memory.None
        }
    }

    private fun linkBindingStart(scope: SourceFilePrinter.Scope, uri: String?, refId: Int?) =
            if (uri != null) {
                "<a href='${Escaper.HTML.escape(uri)}'" +
                        (if (refId != null && referenceThisUrl) " id='${scope.prefix}ref-$refId'" else "") +
                        ">"
            } else {
                ""
            }

    private fun linkBindingEnd(uri: String?): String =
            if (uri != null) {
                "</a>"
            } else {
                ""
            }

    override fun startAnnotation(scope: SourceFilePrinter.Scope,
                                 annotation: SourceAnnotation,
                                 memory: Memory) {
        when (annotation) {
            is BindingRef -> html(linkBindingStart(
                    scope,
                    (memory as Memory.ResolvedBinding).uri,
                    annotation.id))
            is BindingDecl -> {
                val showDeclaration = when (annotation.description) {
                    is BindingDecl.Description.Initializer -> false
                    else -> true
                }

                if (hasOverlay && showDeclaration) {
                    val superUris = (memory as Memory.Decl).superBindingUris
                    var tooltipStartHtml = ""
                    if (annotation.superBindings.isNotEmpty()) {
                        tooltipStartHtml += "<b>Extends</b><ul id='super-types'>" +
                                annotation.superBindings.withIndex().joinToString { (i, binding) ->
                                    "<li>" +
                                            linkBindingStart(scope, superUris[i], refId = null) +
                                            Escaper.HTML.escape(binding.name) +
                                            "</li>"
                                } +
                                "</ul><br>"
                    }
                    for ((realm, bindingUri) in memory.correspondingUris) {
                        val escapedUri = Escaper.HTML.escape(bindingUri.toASCIIString())
                        tooltipStartHtml += when (realm) {
                            Realm.SOURCE -> "<a class='corresponding' href='$escapedUri'><img src='/assets/icons/fileTypes/java.svg'> Source Code</a><br>"
                            Realm.BYTECODE -> "<a class='corresponding' href='$escapedUri'><img src='/assets/icons/fileTypes/javaClass.svg'> Bytecode</a><br>"
                        }
                    }

                    html(
                        "<a class='show-refs' href='javascript:;' onclick='showReferences(this); arguments[0].stopPropagation(); return false' data-binding='${
                            Escaper.HTML.escape(annotation.binding)
                        }' data-tooltip-start-html='${Escaper.HTML.escape(tooltipStartHtml)}' data-realm='${
                            scopes.getValue(scope).realm
                        }' data-artifact-id='${Escaper.HTML.escape(scopes.getValue(scope).artifactId)}'></a>"
                    )
                }

                val id = scope.prefix + annotation.binding
                html("<a id='$id' href='$ownUriString${Locations.bindingHash(id)}'>")
            }
            is Style -> html("<span class='${annotation.styleClass.joinToString(" ")}'>")
            is LocalVariableOrLabelRef -> html("<span class='local-variable' data-local-variable='${annotation.id}'>")
            is SourceLineRef -> html("<a href='/${Escaper.HTML.escape(scopes.getValue(scope).artifactId + '/' + annotation.sourceFile)}#${annotation.line}'>")
            is RenderedJavadoc -> {
                if (renderJavadoc) {
                    val content = Jsoup.parse(annotation.html)
                    content.outputSettings().prettyPrint(false)
                    NodeTraversor.traverse(ApplyBindingLinkVisitor(scopes.getValue(scope)), content)

                    html("<span class='javadoc-render-toggle' title='Toggle Javadoc rendering (sets a cookie)'></span>")
                    html("<span tabindex='-1' class='javadoc-rendered javadoc-rendered-placeholder'>$content</span>")

                    html("<span class='javadoc-raw'>")
                }
            }
        }
    }

    private inner class ApplyBindingLinkVisitor(private val scopeInfo: ScopeInfo) : NodeVisitor {
        override fun head(node: Node, depth: Int) {
            if (node !is Element) return
            val bindingIdString = node.attr(RenderedJavadoc.ATTRIBUTE_BINDING_ID)
            if (bindingIdString.isNullOrEmpty()) return
            val bindingId = RenderedJavadoc.attributeValueToBinding(bindingIdString)
            node.removeAttr(RenderedJavadoc.ATTRIBUTE_BINDING_ID)
            val uri = bindingResolver.resolveBinding(scopeInfo.realm, scopeInfo.classpath, bindingId).firstOrNull()
                ?.toASCIIString() ?: return
            node.attr("href", uri)
        }

        override fun tail(node: Node?, depth: Int) {
        }
    }

    override fun endAnnotation(scope: SourceFilePrinter.Scope,
                               annotation: SourceAnnotation,
                               memory: Memory) {
        when (annotation) {
            is BindingRef -> html(linkBindingEnd((memory as Memory.ResolvedBinding).uri))
            is BindingDecl, is SourceLineRef -> html("</a>")
            is Style, is LocalVariableOrLabelRef -> html("</span>")
            is RenderedJavadoc -> {
                if (renderJavadoc) {
                    html("</span>")
                }
            }
        }
    }

    private fun html(@Language("HTML") s: String) {
        writer.write(s)
    }

    override fun text(s: String, start: Int, end: Int) {
        Escaper.HTML.escape(writer, s, start, end)
    }

    override fun beginInsertion() {
        html("<span class='insertion'>")
    }

    override fun beginDeletion() {
        html("<span class='deletion'>")
    }

    override fun beginHighlight() {
        html("<span class='highlight'>")
    }

    override fun endInsertion() {
        html("</span>")
    }

    override fun endDeletion() {
        html("</span>")
    }

    override fun endHighlight() {
        html("</span>")
    }

    private fun lineMarker(id: String, line: Int, forDiff: Boolean) {
        html("<a href='$ownUriString#$id' ${if (referenceThisUrl) "id='$id'" else ""} class='line${if (forDiff) " line-diff" else ""}' data-line='${line + 1}'></a>")
    }

    override fun diffLineMarker(newLine: Int?, oldLine: Int?) {
        if (oldLine != null) {
            lineMarker("--- ${oldLine + 1}", oldLine, forDiff = true)
        } else {
            html("<a class='line line-diff'></a>")
        }
        if (newLine != null) {
            lineMarker("${newLine + 1}", newLine, forDiff = true)
        } else {
            html("<a class='line line-diff'></a>")
        }
        html("<span class='diff-marker'>")
        when {
            newLine == null -> html("-")
            oldLine == null -> html("+")
            else -> html(" ")
        }
        html("</span>")
    }

    override fun normalLineMarker(line: Int) {
        lineMarker((line + 1).toString(), line, forDiff = false)
    }
}