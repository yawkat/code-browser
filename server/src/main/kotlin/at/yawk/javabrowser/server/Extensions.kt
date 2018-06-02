package at.yawk.javabrowser.server

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/**
 * @author yawkat
 */
fun Element.appendChildren(children: Iterable<Node>) = children.forEach { appendChild(it) }