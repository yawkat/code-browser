package at.yawk.javabrowser.generator.source

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.RenderedJavadoc
import org.apache.commons.lang3.StringEscapeUtils
import org.eclipse.jdt.core.dom.ArrayType
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IDocElement
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IPackageBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.Javadoc
import org.eclipse.jdt.core.dom.MemberRef
import org.eclipse.jdt.core.dom.MethodRef
import org.eclipse.jdt.core.dom.MethodRefParameter
import org.eclipse.jdt.core.dom.Name
import org.eclipse.jdt.core.dom.NameQualifiedType
import org.eclipse.jdt.core.dom.ParameterizedType
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.QualifiedName
import org.eclipse.jdt.core.dom.QualifiedType
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SimpleType
import org.eclipse.jdt.core.dom.TagElement
import org.eclipse.jdt.core.dom.TextElement
import org.eclipse.jdt.core.dom.Type
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.collections.set

private val CLEANER = Cleaner(
    Safelist.none()
        // todo: survey on which tags are used
        // todo: img – but cache images for privacy reasons
        .addTags(
            "a", "b", "blockquote", "br", "cite", "code", "dd", "dl", "dt", "em",
            "i", "li", "ol", "p", "pre", "q", "small", "strike", "strong", "sub",
            "sup", "u", "ul",

            // some more from https://java-browser.yawk.at/java/14/jdk.compiler/com/sun/tools/doclint/HtmlTag.java
            "abbr", "acronym", "address", "article", "aside", "bdi", "big", "caption", "center", "col", "colgroup",
            "dfn", "div", "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "mark", "s",
            "table", "tbody", "td", "tfoot", "th", "thead", "tr", "tt", "wbr", "var"
        )
        .addAttributes("a", "href")
        .addAttributes("abbr", "title")
        .addAttributes("acronym", "title")
        .addAttributes("caption", "align")
        .addAttributes("col", "span", "align", "char", "charoff", "valign", "width")
        .addAttributes("colgroup", "align", "char", "charoff", "valign")
        .addAttributes("td", "colspan", "rowspan", "abbr", "align", "char", "charoff", "height", "valign", "width")
        .addAttributes("th", "colspan", "rowspan", "abbr", "align", "char", "charoff", "height", "valign", "width")
        .addAttributes("tr", "align", "char", "charoff", "height", "valign")
        .addAttributes("dfn", "title")
        .addAttributes("table", "align", "border", "cellpadding", "cellspacing", "frame", "rules", "summary", "width")
        .addProtocols("a", "href", "ftp", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https")
        .addEnforcedAttribute("a", "rel", "nofollow")
        .preserveRelativeLinks(false)
)

@Suppress("UNCHECKED_CAST")
private val TagElement.fragments: List<IDocElement>
    get() = fragments() as List<IDocElement>

/**
 * For the null check.
 */
private fun createTextNode(text: String) = TextNode(text)

class JavadocRenderer(
    private val hashBinding: String.() -> BindingId,
    private val declaringClassQualifiedName: String? = null,
    private val subjectBinding: IBinding? = null
) {

    @Suppress("UNCHECKED_CAST")
    fun render(javadoc: Javadoc): List<Node> {
        val rootTags = javadoc.tags() as List<TagElement>

        val out = ArrayList<Node>(2)

        val mainTags = rootTags.filter { it.tagName == null }
        if (mainTags.isNotEmpty()) {
            val mainElement = Element(Tag.valueOf("div"), "")
            SafeHtmlBuilder().visitChildren(mainTags.single()).appendSafeTo(mainElement)
            out.add(mainElement)
        }

        val secondaryTags = rootTags.filter { it.tagName != null }.toMutableList()
        if (secondaryTags.isNotEmpty()) {
            val secondaryElement = Element(Tag.valueOf("table"), "")
            fun makeEntry(name: String, multi: Boolean = false): Element {
                val tr = secondaryElement.appendElement("tr")
                tr.appendElement("th").appendText(name)
                val td = tr.appendElement("td")
                return if (multi) td.appendElement("ul") else td
            }

            // @throws and @param
            fun appendEmdashSeparatedProperties(list: Element, items: List<TagElement>) {
                for (item in items) {
                    val name = item.fragments.first()
                    val desc = item.fragments.drop(1)
                    val li = list.appendElement("li")
                    SafeHtmlBuilder().visit(name).appendSafeTo(li)
                    li.appendText(" – ")
                    SafeHtmlBuilder().visit(desc, removePrefix = " ").trimEnd().appendSafeTo(li)
                }
            }

            val authors = secondaryTags.filter { it.tagName == TagElement.TAG_AUTHOR }
            if (authors.isNotEmpty()) {
                secondaryTags.removeIf { it.tagName == TagElement.TAG_AUTHOR }
                val content = makeEntry("Author:")
                for ((i, author) in authors.withIndex()) {
                    if (i != 0) content.appendText(", ")
                    SafeHtmlBuilder()
                        .visitChildren(author, removePrefix = " ")
                        .trimEnd()
                        .appendSafeTo(content)
                }
            }

            val params = secondaryTags.filter { it.tagName == TagElement.TAG_PARAM && it.fragments.firstOrNull() is Name }
            if (params.isNotEmpty()) {
                secondaryTags.removeIf { it.tagName == TagElement.TAG_PARAM && it.fragments.firstOrNull() is Name }
                appendEmdashSeparatedProperties(makeEntry("Params:", multi = true), params)
            }

            fun isTypeParam(it: TagElement) =
                it.tagName == TagElement.TAG_PARAM &&
                        (it.fragments.getOrNull(0) as? TextElement)?.text == "<" &&
                        (it.fragments.getOrNull(2) as? TextElement)?.text == ">"
            val typeParams = secondaryTags.filter(::isTypeParam)
            if (typeParams.isNotEmpty()) {
                secondaryTags.removeIf(::isTypeParam)
                val ul = makeEntry("Type parameters:", multi = true)
                for (param in typeParams) {
                    val first = param.fragments.first()
                    require(first is TextElement && first.text == "<")
                    val name = param.fragments[1] as Name
                    val close = param.fragments[2]
                    require(close is TextElement && close.text == ">")
                    val desc = param.fragments.drop(3)
                    val li = ul.appendElement("li").appendText("<")
                    SafeHtmlBuilder().visit(name).appendSafeTo(li)
                    li.appendText("> – ")
                    SafeHtmlBuilder().visit(desc, removePrefix = " ").trimEnd().appendSafeTo(li)
                }
            }

            val throws = secondaryTags.filter {
                it.tagName == TagElement.TAG_THROWS || it.tagName == TagElement.TAG_EXCEPTION
            }
            if (throws.isNotEmpty()) {
                secondaryTags.removeIf { it.tagName == TagElement.TAG_THROWS || it.tagName == TagElement.TAG_EXCEPTION }
                appendEmdashSeparatedProperties(makeEntry("Throws:", multi = true), throws)
            }

            fun handleProvidesSee(tagName: String, title: String) {
                val provides = secondaryTags.filter { it.tagName == tagName }
                if (provides.isNotEmpty()) {
                    secondaryTags.removeIf { it.tagName == tagName }
                    val ul = makeEntry(title, multi = true)
                    for (param in provides) {
                        // current JDT doesn't parse the first param as a name yet
                        val name = (param.fragments.first() as TextElement).text
                        val desc = param.fragments.drop(1)
                        SafeHtmlBuilder()
                            .visitUnsafeText(name.trimStart().replaceFirst(" ", " – "))
                            .visit(desc, removePrefix = " ")
                            .trimEnd()
                            .appendSafeTo(ul.appendElement("li"))
                    }
                }
            }
            handleProvidesSee("@provides", "Provides:")
            handleProvidesSee("@uses", "Uses:")

            fun isSee(it: TagElement) = it.tagName == TagElement.TAG_SEE && it.fragments.isNotEmpty()
            val see = secondaryTags.filter(::isSee)
            if (see.isNotEmpty()) {
                secondaryTags.removeIf(::isSee)
                val ul = makeEntry("See Also:", multi = true)
                for (item in see) {
                    val li = ul.appendElement("li")
                    val fragments = item.fragments
                    val first = fragments.first()
                    if (first is TextElement) {
                        val builder = SafeHtmlBuilder()
                        if (first.text.startsWith(" \"")) {
                            for ((i, child) in fragments.withIndex()) {
                                if (child is TextElement) {
                                    var text = child.text
                                    if (i == 0) text = text.removePrefix(" \"")
                                    builder.visitUnsafeText(text)
                                } else {
                                    builder.visit(child)
                                }
                            }
                            builder.trimEnd().removeSuffix("\"")
                        } else {
                            builder.visit(fragments, removePrefix = " ").trimEnd()
                        }
                        builder.appendSafeTo(li)
                    } else {
                        li.insertChildren(0, renderLinkPlain(item))
                    }
                }
            }

            for (secondaryTag in secondaryTags) {
                val title = when (secondaryTag.tagName) {
                    TagElement.TAG_DEPRECATED -> "Deprecated:"
                    TagElement.TAG_SINCE -> "Since:"
                    TagElement.TAG_RETURN -> "Returns:"
                    TagElement.TAG_VERSION -> "Version:"
                    "@hidden" -> "Hidden"
                    // https://github.com/openjdk/jdk/blob/master/make/Docs.gmk#L68
                    "@apiNote" -> "API Note:"
                    "@implSpec" -> "Implementation Requirements:"
                    "@implNote" -> "Implementation Note:"
                    // should be handled earlier
                    TagElement.TAG_AUTHOR,
                    TagElement.TAG_THROWS,
                    TagElement.TAG_EXCEPTION,
                    "@provides" -> throw AssertionError()
                    // TAG_PARAM fallback if invalid
                    // TAG_SEE fallback if invalid
                    else -> secondaryTag.tagName
                }
                SafeHtmlBuilder()
                    .visitChildren(secondaryTag, removePrefix = " ")
                    .trimEnd()
                    .appendSafeTo(makeEntry(title))
            }
            out.add(secondaryElement)
        }

        return out
    }

    private tailrec fun getSimpleName(name: Name): String = when (name) {
        is SimpleName -> name.identifier
        is QualifiedName -> getSimpleName(name.name)
        else -> throw AssertionError(name.javaClass)
    }

    private fun getSimpleName(type: Type): String = when (type) {
        is PrimitiveType -> type.primitiveTypeCode.toString()
        is SimpleType -> getSimpleName(type.name)
        is QualifiedType -> getSimpleName(type.name)
        is NameQualifiedType -> getSimpleName(type.name)
        is ArrayType -> getSimpleName(type.elementType) + "[]".repeat(type.dimensions)
        is ParameterizedType -> getSimpleName(type.type)
        else -> throw UnsupportedOperationException(type.javaClass.name)
    }

    private fun memberRefToString(
        textBuilder: StringBuilder,
        declaringClassFromMethodBinding: ITypeBinding?,
        fragmentQualifier: Name?,
        fragmentName: SimpleName
    ) {
        // type
        val declaringBinding = declaringClassFromMethodBinding ?: fragmentQualifier?.resolveTypeBinding()
        if (declaringBinding != null) {
            if (declaringBinding.qualifiedName != declaringClassQualifiedName) {
                textBuilder.append(declaringBinding.name).append('.')
            }
        } else if (fragmentQualifier != null) {
            textBuilder.append(getSimpleName(fragmentName)).append('.')
        }
        // name
        textBuilder.append(fragmentName.identifier)
    }

    private fun renderMemberRef(fragment: MemberRef, text: List<Node>? = null): List<Node> {
        val binding = fragment.resolveBinding()
        if (text == null) {
            val textBuilder = StringBuilder()
            memberRefToString(
                textBuilder,
                declaringClassFromMethodBinding = when (binding) {
                    is IMethodBinding -> binding.declaringClass
                    is IVariableBinding -> binding.declaringClass
                    else -> null
                },
                fragmentQualifier = fragment.qualifier,
                fragmentName = fragment.name
            )
            return renderWithOptionalBinding(listOf(createTextNode(textBuilder.toString())), binding)
        } else {
            return renderWithOptionalBinding(text, binding)
        }
    }

    private fun renderMethodRef(fragment: MethodRef, text: List<Node>? = null): List<Node> {
        val methodBinding = fragment.resolveBinding() as? IMethodBinding
        if (text == null) {
            val textBuilder = StringBuilder()

            if (methodBinding != null && methodBinding.isConstructor) {
                textBuilder.append(fragment.name.identifier)
            } else {
                memberRefToString(
                    textBuilder,
                    declaringClassFromMethodBinding = methodBinding?.declaringClass,
                    fragmentQualifier = fragment.qualifier,
                    fragmentName = fragment.name
                )
            }

            // params
            textBuilder.append('(')
            val paramIndices = fragment.parameters().indices
            for (i in paramIndices) {
                if (i != 0) textBuilder.append(", ")
                val parameter = fragment.parameters()[i] as MethodRefParameter
                val paramBinding = methodBinding?.parameterTypes?.getOrNull(i) ?: parameter.type.resolveBinding()
                // we could guess the varargs from the resolved method here, but `javadoc` doesn't, so leave it be
                val varargs = parameter.isVarargs
                if (paramBinding != null) {
                    val strippedBinding =
                        if (varargs && paramBinding.isArray) paramBinding.componentType else paramBinding
                    textBuilder.append(strippedBinding.name)
                } else {
                    textBuilder.append(getSimpleName(parameter.type))
                }
                if (varargs) {
                    textBuilder.append("...")
                }
                if (parameter.name != null) {
                    textBuilder.append(' ').append(parameter.name.identifier)
                }
            }
            textBuilder.append(')')
            return renderWithOptionalBinding(listOf(createTextNode(textBuilder.toString())), methodBinding)
        } else {
            return renderWithOptionalBinding(text, methodBinding)
        }
    }

    private fun renderName(fragment: Name, text: List<Node>? = null): List<Node> {
        return renderWithOptionalBinding(
            text ?: listOf(createTextNode(getSimpleName(fragment))),
            fragment.resolveBinding()
        )
    }

    private fun renderWithOptionalBinding(
        content: List<Node>,
        binding: IBinding?
    ): List<Node> {
        val bindingString = when (binding) {
            is ITypeBinding -> Bindings.toString(binding)
            is IMethodBinding -> Bindings.toString(binding)
            is IPackageBinding -> Bindings.toString(binding)
            is IVariableBinding -> Bindings.toString(binding)
            else -> null
        }
        if (bindingString == null) {
            return content
        } else {
            val anchor = Element(Tag.valueOf("a"), "")
            anchor.attr(
                RenderedJavadoc.ATTRIBUTE_BINDING_ID,
                RenderedJavadoc.bindingToAttributeValue(bindingString.hashBinding())
            )
            anchor.insertChildren(0, content)
            return listOf(anchor)
        }
    }

    private companion object {
        private val SUBSTITUTION_PREFIX = Base64.getEncoder().encodeToString(
            ByteArray(12).also { ThreadLocalRandom.current().nextBytes(it) })
        private const val SUBSTITUTION_KEY_LENGTH = 8
    }

    private inner class SafeHtmlBuilder {
        /**
         * Don't edit directly, use [visitUnsafeText] and [visitSpecialText]
         */
        private val unsafeHtmlBuilder = StringBuilder()

        private val substitutions = mutableMapOf<String, List<Node>>()

        private fun appendSafeHtml(safeHtml: List<Node>) {
            if (safeHtml.isNotEmpty()) {
                val substitutionKey = String.format("%0${SUBSTITUTION_KEY_LENGTH}x", substitutions.size)
                visitSpecialText(SUBSTITUTION_PREFIX)
                visitSpecialText(substitutionKey)

                substitutions[substitutionKey] = safeHtml
            }
        }

        fun visitChildren(fragment: TagElement, removePrefix: String = ""): SafeHtmlBuilder {
            return visit(fragment.fragments, removePrefix)
        }

        fun visit(fragments: List<IDocElement>, removePrefix: String = ""): SafeHtmlBuilder {
            for ((childIndex, child) in fragments.withIndex()) {
                visit(child, if (childIndex == 0) removePrefix else "")
            }
            return this
        }

        fun visit(fragment: IDocElement, removePrefix: String = ""): SafeHtmlBuilder {
            when (fragment) {
                is TextElement -> {
                    visitUnsafeText(fragment.text.removePrefix(removePrefix))
                }
                is TagElement -> when (val handler = TAG_HANDLERS[fragment.tagName] ?: TagHandler.PrintUnmodified) {
                    is TagHandler.PrintUnmodified -> {
                        visitSpecialText('{' + fragment.tagName)
                        visitChildren(fragment)
                        visitSpecialText("}")
                    }
                    is TagHandler.PrintContentUnmodified -> {
                        // content may start with a space, skip that
                        visitChildren(fragment, removePrefix = " ")
                    }
                    is TagHandler.ProduceHtml -> {
                        appendSafeHtml(handler.produce(fragment))
                    }
                }
                is MemberRef -> appendSafeHtml(renderMemberRef(fragment))
                is MethodRef -> appendSafeHtml(renderMethodRef(fragment))
                is Name -> appendSafeHtml(renderName(fragment))
                else -> throw AssertionError(fragment.javaClass)
            }
            return this
        }

        // the distinction between the two following elements used to be relevant before I patched AbstractCommentParser
        // to include whitespace.

        /**
         * Add normal HTML to this builder. Must come from a [TextElement].
         */
        fun visitUnsafeText(text: String): SafeHtmlBuilder {
            unsafeHtmlBuilder.append(text)
            return this
        }

        /**
         * Add generated HTML from non-[TextElement] sources.
         */
        fun visitSpecialText(text: String): SafeHtmlBuilder {
            unsafeHtmlBuilder.append(text)
            return this
        }

        /**
         * Replace any substitutions in the given `input` by the substitutions registered in [substitutions].
         */
        private fun insertSafeHtml(
            input: String,
            onText: (String) -> Unit,
            onNodes: (List<Node>) -> Unit
        ) {
            var i = 0
            while (i < input.length) {
                val start = input.indexOf(SUBSTITUTION_PREFIX, i)
                if (start == -1) break
                if (i < start) {
                    onText(input.substring(i, start))
                }
                i = start + SUBSTITUTION_PREFIX.length
                val key = input.substring(i, i + SUBSTITUTION_KEY_LENGTH)
                onNodes(substitutions.getValue(key))
                i += SUBSTITUTION_KEY_LENGTH
            }
            if (i < input.length) {
                onText(input.substring(i))
            }
        }

        /**
         * Insert safe HTML fragments from [substitutions] into the given node.
         *
         * @return if not `null`, replace the node with the returned list of nodes. If `null`, no replacement is necessary.
         */
        private fun insertSafeHtml(node: Node): List<Node>? {
            when (node) {
                is Element -> {
                    var i = 0
                    while (i < node.childNodeSize()) {
                        val childNode = node.childNode(i)
                        val withSafe = insertSafeHtml(childNode)
                        if (withSafe == null) {
                            // no modification necessary, proceed
                            i++
                        } else {
                            // replace this child node
                            childNode.remove()
                            node.insertChildren(i, withSafe)
                            i += withSafe.size
                        }
                    }
                    for (attribute in node.attributes()) {
                        if (attribute.value.contains(SUBSTITUTION_PREFIX)) {
                            val replacementBuilder = StringBuilder()
                            insertSafeHtml(
                                attribute.value,
                                onText = { replacementBuilder.append(it) },
                                onNodes = {
                                    for (n in it) {
                                        if (n is TextNode) {
                                            replacementBuilder.append(n.text())
                                        } else {
                                            throw IllegalArgumentException("HTML-generating taglet inside attribute")
                                        }
                                    }
                                }
                            )
                        }
                    }
                    return null // don't need to replace, just mutate Element
                }
                is TextNode -> {
                    val text = node.text()
                    if (text.contains(SUBSTITUTION_PREFIX)) {
                        val out = ArrayList<Node>()
                        insertSafeHtml(
                            text,
                            onText = { out.add(createTextNode(it)) },
                            onNodes = { out.addAll(it) }
                        )
                        return out
                    } else {
                        // nothing to replace
                        return null
                    }
                }
                else -> return emptyList() // remove other nodes entirely
            }
        }

        private fun buildCleanNodesWithoutSubstitution(context: Element?): List<Node> {
            val baseUri = context?.baseUri() ?: ""
            val dirtyNodes = Parser.parseFragment(unsafeHtmlBuilder.toString(), context, baseUri)
            val dirtyShell = Document.createShell(baseUri)
            dirtyShell.body().insertChildren(0, dirtyNodes)
            val cleanShell = CLEANER.clean(dirtyShell)

            // need to make a copy in case the nodes are modified and disappear from .childNodes()
            return ArrayList(cleanShell.body().childNodes())
        }

        fun trimEnd(): SafeHtmlBuilder {
            while (unsafeHtmlBuilder.isNotEmpty() && unsafeHtmlBuilder.last().isWhitespace()) {
                unsafeHtmlBuilder.setLength(unsafeHtmlBuilder.length - 1)
            }
            return this
        }

        fun removeSuffix(suffix: String): SafeHtmlBuilder {
            if (unsafeHtmlBuilder.endsWith(suffix)) {
                unsafeHtmlBuilder.setLength(unsafeHtmlBuilder.length - suffix.length)
            }
            return this
        }

        fun build(): List<Node> {
            val cleanNodes = buildCleanNodesWithoutSubstitution(null)
            return cleanNodes.flatMap { insertSafeHtml(it) ?: listOf(it) }
        }

        fun appendSafeTo(target: Element) {
            val cleanNodes = buildCleanNodesWithoutSubstitution(target)
            for (childNode in cleanNodes) {
                val withSafe = insertSafeHtml(childNode)
                if (withSafe == null) {
                    // no modification necessary, proceed
                    target.appendChild(childNode)
                } else {
                    // replace this child node
                    target.insertChildren(target.childNodeSize(), withSafe)
                }
            }
        }
    }

    private fun renderLinkPlain(fragment: TagElement): List<Node> {
        val fragments = fragment.fragments
        if (fragments.isEmpty()) {
            // invalid
            return emptyList()
        }
        val refFragment = fragments.first()
        val labelFragments = fragments.drop(1)
        var label =
            if (labelFragments.isEmpty()) null
            else SafeHtmlBuilder().visit(labelFragments, removePrefix = " ").trimEnd().build()
        if (label.isNullOrEmpty()) label = null
        return when (refFragment) {
            is MemberRef -> renderMemberRef(refFragment, label)
            is MethodRef -> renderMethodRef(refFragment, label)
            is Name -> renderName(refFragment, label)
            is TextElement -> {
                // this is invalid, but sometimes libraries don't properly link stuff,
                // e.g. {@link Cipher.doFinal(byte[])} (note the dot instead of #).
                // still respect the label rules though.
                if (label == null) {
                    renderWithOptionalBinding(
                        listOf(createTextNode(refFragment.text.removePrefix(" "))),
                        binding = null
                    )
                } else {
                    renderWithOptionalBinding(label, binding = null)
                }
            }
            else -> throw IllegalArgumentException(refFragment.javaClass.name)
        }
    }

    private fun constantValueToString(constantValue: Any) =
        if (constantValue is String) "\"${StringEscapeUtils.escapeJava(constantValue)}\""
        else constantValue.toString()

    private val TAG_HANDLERS = mapOf(
        TagElement.TAG_CODE to object : TagHandler.ProduceHtml() {
            override fun produce(fragment: TagElement): List<Node> {
                val code = Element(Tag.valueOf("code"), "")
                for ((i, child) in fragment.fragments.withIndex()) {
                    var text = (child as TextElement).text
                    if (i == 0) {
                        text = text.removePrefix(" ")
                    }
                    code.appendText(text)
                }
                return listOf(code)
            }
        },
        TagElement.TAG_LITERAL to object : TagHandler.ProduceHtml() {
            override fun produce(fragment: TagElement): List<Node> {
                return fragment.fragments.mapIndexed { i, child ->
                    var text = (child as TextElement).text
                    if (i == 0) {
                        text = text.removePrefix(" ")
                    }
                    createTextNode(text)
                }
            }
        },
        "@index" to object : TagHandler.ProduceHtml() {
            override fun produce(fragment: TagElement): List<Node> {
                val text = fragment.fragments.joinToString(" ") { (it as TextElement).text }.removePrefix(" ")
                val phrase: String
                val description: String
                if (text.startsWith('"')) {
                    // {@index "phrase with spaces" description}
                    val sep = text.indexOf('"', startIndex = 1)
                    phrase = text.substring(1, sep)
                    description = text.substring(sep + 1).removePrefix(" ")
                } else {
                    // {@index phrase description}
                    val sep = text.indexOf(' ')
                    if (sep == -1) {
                        phrase = text
                        description = text
                    } else {
                        phrase = text.substring(0, sep)
                        description = text.substring(sep + 1).removePrefix(" ")
                    }
                }
                if (phrase == description || description.isBlank()) {
                    return listOf(createTextNode(phrase))
                } else {
                    return listOf(
                        Element(Tag.valueOf("span"), "")
                            .attr("title", description)
                            .attr("class", "javadoc-tag-index")
                            .appendText(phrase)
                    )
                }
            }
        },
        TagElement.TAG_DOCROOT to TagHandler.PrintUnmodified,
        TagElement.TAG_INHERITDOC to TagHandler.PrintUnmodified,
        TagElement.TAG_LINKPLAIN to object : TagHandler.ProduceHtml() {
            override fun produce(fragment: TagElement): List<Node> {
                return renderLinkPlain(fragment)
            }
        },
        TagElement.TAG_LINK to object : TagHandler.ProduceHtml() {
            override fun produce(fragment: TagElement): List<Node> {
                return listOf(Element(Tag.valueOf("code"), "").insertChildren(0, renderLinkPlain(fragment)))
            }
        },
        "@summary" to TagHandler.PrintContentUnmodified,
        "@systemProperty" to TagHandler.PrintContentUnmodified,
        TagElement.TAG_VALUE to object : TagHandler.ProduceHtml() {
            override fun produce(fragment: TagElement): List<Node> {
                val fragments = fragment.fragments
                val refFragment: MemberRef?
                val refBinding: IVariableBinding?
                val sameField: Boolean
                if (fragments.isEmpty()) {
                    refFragment = null
                    refBinding = subjectBinding as? IVariableBinding
                    sameField = true
                } else {
                    refFragment = fragments.first() as? MemberRef
                    require(fragments.drop(1).all { it is TextElement && it.text.isBlank() })
                    refBinding = refFragment?.resolveBinding() as? IVariableBinding
                    sameField = false
                }
                val constantValueString = refBinding?.constantValue?.let { constantValueToString(it) }
                if (refFragment == null) {
                    if (sameField && subjectBinding != null && constantValueString != null) {
                        return listOf(createTextNode(constantValueString))
                    } else {
                        return SafeHtmlBuilder()
                            .visitSpecialText("{@value")
                            .visitChildren(fragment)
                            .visitSpecialText("}")
                            .build()
                    }
                } else {
                    return renderMemberRef(
                        refFragment,
                        if (constantValueString != null) listOf(createTextNode(constantValueString)) else null
                    )
                }
            }
        }
    )

    private sealed class TagHandler {
        object PrintUnmodified : TagHandler()
        object PrintContentUnmodified : TagHandler()

        abstract class ProduceHtml : TagHandler() {
            abstract fun produce(fragment: TagElement): List<Node>
        }
    }
}