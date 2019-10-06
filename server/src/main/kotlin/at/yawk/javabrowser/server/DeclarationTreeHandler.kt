package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.PackageNode
import at.yawk.javabrowser.server.view.PackageNodeView
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.util.Collections

/**
 * @author yawkat
 */
class DeclarationTreeHandler(
        private val dbi: DBI,
        private val ftl: Ftl,
        private val objectMapper: ObjectMapper
) : HttpHandler {
    companion object {
        const val PATTERN = "/declarationTree"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val artifactId = exchange.queryParameters["artifactId"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass artifact ID")
        val binding = exchange.queryParameters["binding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass binding")

        // TODO: handle binding source file

        dbi.inTransaction { conn: Handle, _ ->
            val json = conn.select("select path, json from sourceFiles where artifactid = ? and path = (select sourceFile from bindings where bindings.artifactid = ? and binding = ?)", artifactId, artifactId, binding)

            if (json.isNotEmpty()) {
                val sourceFile = objectMapper.readValue(
                        json.single()["json"] as ByteArray,
                        AnnotatedSourceFile::class.java)
                for (topLevelType in declarationTree(sourceFile)) {
                    if (topLevelType.declaration.binding == binding) {
                        ftl.render(exchange, PackageNodeView(topLevelType.children, "/$artifactId/${json.single()["path"]}"))
                        return@inTransaction
                    }
                }
                throw HttpException(404, "Binding not found - is it a top-level declaration?")
            } else {
                // the binding must be a package
                val iterator = packageTree(conn, artifactId, binding)
                // this is also some protection against XSS because it checks that `binding` is actually a valid package
                if (!iterator.hasNext()) throw HttpException(404, "Binding not found")
                ftl.render(exchange, PackageNodeView(iterator))
            }
        }
    }

    fun packageTree(conn: Handle, artifactId: String, packageName: String? = null): Iterator<PackageNode> {
        val itemsByDepth = conn.createQuery("select depth, typeCount from data.type_count_by_depth_view where artifactId = ? and (package = ? or ?)")
                .bind(0, artifactId)
                .bind(1, packageName)
                .bind(2, packageName == null)
                .map { _, r, _ ->
                    r.getInt("depth") to r.getInt("typeCount")
                }
                .toMap()
        var expectedItemCount = 0
        var depth = 0
        do {
            expectedItemCount += itemsByDepth[depth] ?: 0
            depth++
        } while (expectedItemCount < 1000 && itemsByDepth.containsKey(depth))

        val types = Iterators.peekingIterator(conn.createQuery(
                """
                    select name, description, modifiers, sourcefile 
                    from (
                        select artifactId, binding as name, description, modifiers, sourcefile from data.bindings where parent is null 
                        union select artifactId, name, NULL as description, 0 as modifiers, NULL as sourcefile from data.packages_view
                    ) sq where artifactId = ? and name like ? and data.count_dots(name) < ? order by name""")
                .bind(0, artifactId)
                .bind(1, if (packageName == null) "%" else "$packageName.%")
                .bind(2, depth + (packageName?.count { it == '.' } ?: -1))
                .map { _, rs, _ ->
                    val binding = rs.getString(1)
                    val description: ByteArray? = rs.getBytes(2)
                    val modifiers = rs.getInt(3)
                    val sourceFile: String? = rs.getString(4)
                    if (description == null) {
                        // children are added later
                        PackageNode.Package(artifactId, binding, binding, Collections.emptyIterator())
                    } else {
                        PackageNode.Type(artifactId, DeclarationNode(BindingDecl(
                                binding,
                                parent = null,
                                description = objectMapper.readValue(description,
                                        BindingDecl.Description::class.java),
                                modifiers = modifiers
                        ), children = Collections.emptyIterator()), fullSourceFilePath = "/$artifactId/$sourceFile")
                    }
                }
                .iterator())

        class PrefixIterator(
                /* work around kotlinc bug: Passing a captured variable to the super constructor is broken */
                types: PeekingIterator<PackageNode>,
                val prefix: String) : TreeIterator<PackageNode, PackageNode>(types) {
            override fun mapOneItem(): PackageNode {
                var item = flatDelegate.next()
                if (item is PackageNode.Package) {
                    // add children to package
                    val subIterator = PrefixIterator(flatDelegate, item.fullName + '.')
                    registerSubIterator(subIterator)
                    val relativeBinding = item.fullName.removePrefix(prefix)
                    item = PackageNode.Package(item.artifactId, item.fullName, relativeBinding, subIterator)
                }
                return item
            }

            override fun returnToParent(item: PackageNode): Boolean {
                return !item.fullName.startsWith(prefix)
            }
        }

        return PrefixIterator(types, "")
    }

    fun declarationTree(sourceFile: AnnotatedSourceFile): Iterator<DeclarationNode> {
        val flat = Iterators.peekingIterator(sourceFile.declarations)

        class SubListItr(
                /* work around kotlinc bug: Passing a captured variable to the super constructor is broken */
                flat: PeekingIterator<BindingDecl>,
                val parent: String?
        ) : TreeIterator<BindingDecl, DeclarationNode>(flat) {
            override fun mapOneItem(): DeclarationNode {
                val item = flatDelegate.next()
                assert(!returnToParent(item)) // checked in hasNext
                val newItr = SubListItr(flatDelegate, item.binding)
                registerSubIterator(newItr)
                return DeclarationNode(item, newItr)
            }

            override fun returnToParent(item: BindingDecl): Boolean {
                // sanity check. The topmost iterator should never return to its non-existent parent
                if (parent == null && item.parent != null) throw AssertionError()
                return item.parent != parent
            }
        }

        return SubListItr(flat, null)
    }
}