package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.TsVector
import at.yawk.javabrowser.generator.GeneratorSourceFile
import at.yawk.javabrowser.generator.db.Transaction
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.testng.annotations.Test

class ArtifactPrinterTest {
    @Test
    fun test() {
        // don't tell cheeser that I'm using mocks
        val tx = mockk<Transaction>(relaxed = true)
        val text = "package foo; class Test {}"
        val metadata = ArtifactMetadata()
        val description = BindingDecl.Description.Type(
            kind = BindingDecl.Description.Type.Kind.CLASS,
            binding = BindingId(123),
            simpleName = "Test",
            typeParameters = emptyList()
        )
        val sourceFile = GeneratorSourceFile(pkg = "foo", text = text)
        sourceFile.annotate(
            0, 1, BindingDecl(
                id = BindingId(123),
                corresponding = mapOf(Realm.BYTECODE to BindingId(234)),
                binding = "foo.Test",
                modifiers = 34,
                parent = BindingId(456),
                superBindings = listOf(
                    BindingDecl.Super(
                        "foo.Bar",
                        BindingId(567)
                    )
                ),
                description = description
            )
        )
        sourceFile.bake()
        runBlocking {
            ArtifactPrinter.with(
                tx = tx,
                id = 0,
                stringId = "test",
                metadata = PrepareArtifactWorker.Metadata(
                    dependencyArtifactIds = listOf("dep1", "dep2"),
                    artifactMetadata = metadata,
                    aliases = listOf("alias1", "alias2")
                ),
                task = {
                    it.addSourceFile(
                        "foo/Test.java",
                        sourceFile,
                        listOf(Tokenizer.Token("foo", 1, 2, false), Tokenizer.Token("bar", 3, 3, true)),
                        Realm.SOURCE
                    )
                }
            )
        }
        coVerify {
            tx.insertArtifact(
                id = 0,
                stringId = "test",
                compilerVersion = any(),
                metaBytes = ArtifactPrinter.jsonMapper.writeValueAsBytes(metadata)
            )
            tx.insertAlias(0, "alias1")
            tx.insertAlias(0, "alias2")
            tx.insertDependency(0, "dep1")
            tx.insertDependency(0, "dep2")
            tx.insertSourceFile(
                realm = Realm.SOURCE,
                artifactId = 0,
                sourceFileId = 0,
                hash = any(),
                path = "foo/Test.java",
                textBytes = text.toByteArray(),
                annotationBytes = ArtifactPrinter.cborMapper.writeValueAsBytes(sourceFile.entries)
            )
            val normalLexemes = TsVector()
            normalLexemes.add("foo", 0)
            normalLexemes.add("bar", 1)
            tx.insertLexemes(
                set = Transaction.FullTextSearchSet.NORMAL,
                realm = Realm.SOURCE,
                artifactId = 0L, sourceFileId = 0L,
                lexemes = normalLexemes,
                starts = intArrayOf(1, 3),
                lengths = intArrayOf(2, 3)
            )
            val noSymbolLexemes = TsVector()
            noSymbolLexemes.add("foo", 0)
            tx.insertLexemes(
                set = Transaction.FullTextSearchSet.NO_SYMBOLS,
                realm = Realm.SOURCE,
                artifactId = 0L, sourceFileId = 0L,
                lexemes = noSymbolLexemes,
                starts = intArrayOf(1),
                lengths = intArrayOf(2)
            )
            tx.insertDecl(
                realm = Realm.SOURCE,
                artifactId = 0,
                bindingId = BindingId(123),
                binding = "foo.Test",
                sourceFileId = 0,
                includeInTypeSearch = true,
                descBytes = ArtifactPrinter.cborMapper.writeValueAsBytes(description),
                modifiers = 34,
                parent = BindingId(456)
            )
            // parent package
            tx.insertDecl(
                realm = Realm.SOURCE,
                artifactId = 0,
                bindingId = BindingId(any()),
                binding = "foo",
                sourceFileId = null,
                includeInTypeSearch = false,
                descBytes = ArtifactPrinter.cborMapper.writeValueAsBytes(BindingDecl.Description.Package),
                modifiers = 0,
                parent = BindingId.TOP_LEVEL_PACKAGE
            )
            // top-level package
            tx.insertDecl(
                realm = Realm.SOURCE,
                artifactId = 0,
                bindingId = BindingId.TOP_LEVEL_PACKAGE,
                binding = "",
                sourceFileId = null,
                includeInTypeSearch = false,
                descBytes = ArtifactPrinter.cborMapper.writeValueAsBytes(BindingDecl.Description.Package),
                modifiers = 0,
                parent = null
            )
        }
        confirmVerified(tx)
    }
}