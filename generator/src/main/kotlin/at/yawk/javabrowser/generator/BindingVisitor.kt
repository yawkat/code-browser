package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.LocalVariableRef
import com.google.common.hash.Hashing
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.Annotation
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration
import org.eclipse.jdt.core.dom.ArrayType
import org.eclipse.jdt.core.dom.Assignment
import org.eclipse.jdt.core.dom.CastExpression
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.Comment
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ConstructorInvocation
import org.eclipse.jdt.core.dom.CreationReference
import org.eclipse.jdt.core.dom.EnumConstantDeclaration
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.ExpressionMethodReference
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.ImportDeclaration
import org.eclipse.jdt.core.dom.Initializer
import org.eclipse.jdt.core.dom.InstanceofExpression
import org.eclipse.jdt.core.dom.IntersectionType
import org.eclipse.jdt.core.dom.Javadoc
import org.eclipse.jdt.core.dom.LambdaExpression
import org.eclipse.jdt.core.dom.MarkerAnnotation
import org.eclipse.jdt.core.dom.MemberRef
import org.eclipse.jdt.core.dom.MemberValuePair
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.MethodRef
import org.eclipse.jdt.core.dom.MethodReference
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.Name
import org.eclipse.jdt.core.dom.NameQualifiedType
import org.eclipse.jdt.core.dom.NormalAnnotation
import org.eclipse.jdt.core.dom.ParameterizedType
import org.eclipse.jdt.core.dom.PostfixExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.QualifiedName
import org.eclipse.jdt.core.dom.QualifiedType
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SimpleType
import org.eclipse.jdt.core.dom.SingleMemberAnnotation
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.SuperConstructorInvocation
import org.eclipse.jdt.core.dom.SuperFieldAccess
import org.eclipse.jdt.core.dom.SuperMethodInvocation
import org.eclipse.jdt.core.dom.SuperMethodReference
import org.eclipse.jdt.core.dom.ThisExpression
import org.eclipse.jdt.core.dom.Type
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.TypeLiteral
import org.eclipse.jdt.core.dom.TypeMethodReference
import org.eclipse.jdt.core.dom.TypeParameter
import org.eclipse.jdt.core.dom.UnionType
import org.eclipse.jdt.core.dom.VariableDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationExpression
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.eclipse.jdt.core.dom.VariableDeclarationStatement
import org.eclipse.jdt.core.dom.WildcardType
import org.eclipse.jdt.internal.compiler.ast.ReferenceExpression
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import java.lang.Long

internal class BindingVisitor(
        private val ast: CompilationUnit,
        private val annotatedSourceFile: AnnotatedSourceFile
) : ASTVisitor(true) {
    private var refIdCounter = 0
    private var initializerCounter = 0
    private var inJavadoc = false

    var lastVisited: ASTNode? = null

    override fun preVisit2(node: ASTNode?): Boolean {
        lastVisited = node
        return true
    }

    private fun makeBindingRef(type: BindingRefType, s: String): BindingRef {
        return BindingRef(
                if (inJavadoc) BindingRefType.JAVADOC else type,
                s,
                refIdCounter++
        )
    }

    override fun visit(node: TypeDeclaration) = visitTypeDecl(node)

    override fun visit(node: AnnotationTypeDeclaration) = visitTypeDecl(node)

    override fun visit(node: EnumDeclaration) = visitTypeDecl(node)

    private fun visitTypeDecl(node: AbstractTypeDeclaration): Boolean {
        val resolved = node.resolveBinding()

        val superclassType = (node as? TypeDeclaration)?.superclassType
        val interfaceTypes = (node as? TypeDeclaration)?.superInterfaceTypes()
        val nameStartPosition = node.name.startPosition
        val declAnnotationLength = node.name.length

        visitTypeDecl(resolved, nameStartPosition, declAnnotationLength, superclassType, interfaceTypes)
        return true
    }

    private fun describeType(resolved: ITypeBinding): BindingDecl.Description.Type {
        var possibleExceptionType: ITypeBinding? = resolved
        while (possibleExceptionType != null && possibleExceptionType.qualifiedName != "java.lang.Throwable") {
            possibleExceptionType = possibleExceptionType.superclass
        }
        // resolved.erasure is null for some reason in some cases
        val erasure = resolved.erasure ?: resolved
        return BindingDecl.Description.Type(
                kind = when {
                    possibleExceptionType != null -> BindingDecl.Description.Type.Kind.EXCEPTION
                    resolved.isAnnotation -> BindingDecl.Description.Type.Kind.ANNOTATION
                    resolved.isEnum -> BindingDecl.Description.Type.Kind.ENUM
                    resolved.isInterface -> BindingDecl.Description.Type.Kind.INTERFACE
                    else -> BindingDecl.Description.Type.Kind.CLASS
                },
                binding = if (erasure.qualifiedName.isEmpty()) null
                else Bindings.toString(resolved),
                simpleName =
                if (resolved.isAnonymous) resolved.binaryName.substring(resolved.binaryName.indexOf('$'))
                else erasure.name,
                typeParameters = resolved.typeArguments.map { describeType(it) }
        )
    }

    private fun visitTypeDecl(resolved: ITypeBinding,
                              nameStartPosition: Int,
                              declAnnotationLength: Int,
                              superclassType: Type?,
                              interfaceTypes: List<Any?>?) {

        val superBindings = ArrayList<BindingDecl.Super>()
        if (superclassType != null) {
            val r = superclassType.resolveBinding()
            val b = r?.let { Bindings.toString(it) }
            if (b != null) {
                superBindings.add(BindingDecl.Super(r.name, b))
            }
            visitType0(superclassType, BindingRefType.SUPER_TYPE)
        } else {
            val r = resolved.superclass
            val b = r?.let { Bindings.toString(it) }
            if (b != null) {
                superBindings.add(BindingDecl.Super(r.name, b))
                annotatedSourceFile.annotate(
                        nameStartPosition, 0, makeBindingRef(BindingRefType.SUPER_TYPE, b))
            }
        }
        if (interfaceTypes != null && interfaceTypes.isNotEmpty()) {
            for (interfaceType in interfaceTypes) {
                val r = (interfaceType as Type).resolveBinding()
                val b = r?.let { Bindings.toString(it) }
                if (b != null) {
                    superBindings.add(BindingDecl.Super(r.name, b))
                }
                visitType0(interfaceType, BindingRefType.SUPER_TYPE)
            }
        } else {
            for (interfaceType in resolved.interfaces) {
                val b = interfaceType.let { Bindings.toString(it) }
                if (b != null) {
                    superBindings.add(BindingDecl.Super(interfaceType.name, b))
                    annotatedSourceFile.annotate(
                            nameStartPosition, 0, makeBindingRef(BindingRefType.SUPER_TYPE, b))
                }
            }
        }

        val binding = Bindings.toString(resolved)
        if (binding != null) {
            val parent = if (resolved.isLocal) {
                parentToString(resolved.declaringMember)
            } else {
                parentToString(resolved.declaringClass)
            }
            annotatedSourceFile.annotate(nameStartPosition, declAnnotationLength, BindingDecl(
                    binding,
                    parent = parent,
                    description = describeType(resolved),
                    modifiers = getModifiers(resolved),
                    superBindings = superBindings
            ))
        }
    }

    override fun visit(node: TypeParameter): Boolean {
        for (bound in node.typeBounds()) {
            visitType0(bound as Type, BindingRefType.TYPE_CONSTRAINT)
        }
        return false
    }

    private fun visitMethodDecl(target: ASTNode, resolved: IMethodBinding?) {
        if (resolved != null) {
            val overrides = annotateOverrides(target, resolved)

            val binding = Bindings.toString(resolved)
            if (binding != null) {
                annotatedSourceFile.annotate(
                        target,
                        BindingDecl(
                                binding = binding,
                                parent = parentToString(resolved.declaringClass),
                                description = describeMethod(resolved),
                                modifiers = getModifiers(resolved),
                                superBindings = overrides.mapNotNull {
                                    val b = Bindings.toString(it) ?: return@mapNotNull null
                                    BindingDecl.Super(it.declaringClass.name + "." + it.name, b)
                                }
                        )
                )
            }
        }
    }

    private fun describeMethod(resolved: IMethodBinding): BindingDecl.Description.Method {
        return BindingDecl.Description.Method(
                name = resolved.name,
                returnTypeBinding = describeType(resolved.returnType),
                parameterTypeBindings = resolved.parameterTypes.map { describeType(it) }
        )
    }

    override fun visit(node: MethodDeclaration): Boolean {
        visitMethodDecl(node.name, node.resolveBinding())
        val ret = node.returnType2
        if (ret != null) visitType0(ret, BindingRefType.RETURN_TYPE)
        for (thrownExceptionType in node.thrownExceptionTypes()) {
            visitType0(thrownExceptionType as Type, BindingRefType.THROWS_DECLARATION)
        }
        return true
    }

    override fun visit(node: Initializer): Boolean {
        @Suppress("MoveVariableDeclarationIntoWhen")
        val parentNode = node.parent
        val declaring = when (parentNode) {
            is AbstractTypeDeclaration -> parentNode.resolveBinding()
            is AnonymousClassDeclaration -> parentNode.resolveBinding()
            else -> throw AssertionError(parentNode.javaClass.name)
        }
        val static = Modifier.isStatic(node.modifiers)
        // TODO: find a binding for this initializer block
        val binding = Bindings.toString(declaring) + "#${if (static) "<clinit>" else "<init>"}${initializerCounter++}"
        //val internal = unsafeGetInternalNode(node) as org.eclipse.jdt.internal.compiler.ast.Initializer
        //val binding = unsafeGetMethodBinding(node.ast, internal.methodBinding)!!
        annotatedSourceFile.annotate(
                node.startPosition, 0,
                BindingDecl(
                        binding = binding,
                        parent = parentToString(declaring),
                        description = BindingDecl.Description.Initializer,
                        modifiers = node.modifiers
                )
        )
        return true
    }

    override fun visit(node: AnnotationTypeMemberDeclaration): Boolean {
        visitMethodDecl(node.name, node.resolveBinding())
        visitType0(node.type, BindingRefType.RETURN_TYPE)
        return true
    }

    private fun annotateOverrides(targetNode: ASTNode, method: IMethodBinding): List<IMethodBinding> {
        if (method.isConstructor || Modifier.isStatic(method.modifiers)) return emptyList()

        val found = ArrayList<IMethodBinding>()

        fun visit(typeBinding: ITypeBinding?) {
            if (typeBinding == null) return

            for (candidate in typeBinding.declaredMethods) {
                if (method.overrides(candidate)) {
                    val alreadyPresent = found.any { it.overrides(candidate) }
                    if (!alreadyPresent) {
                        found.add(candidate)
                        val ref = Bindings.toString(candidate)
                        if (ref != null) {
                            annotatedSourceFile.annotate(targetNode,
                                    makeBindingRef(BindingRefType.SUPER_METHOD, ref))
                        }
                    }
                }
            }

            visit(typeBinding.superclass)
            typeBinding.interfaces.forEach { visit(it) }
        }

        fun visitSupers(typeBinding: ITypeBinding) {
            visit(typeBinding.superclass)
            typeBinding.interfaces.forEach { visit(it) }
        }

        visitSupers(method.declaringClass ?: return emptyList())

        return found
    }

    override fun visit(node: VariableDeclarationFragment): Boolean {
        val resolved = node.resolveBinding()
        visitFieldDecl(resolved, node.name)
        return true
    }

    override fun visit(node: SingleVariableDeclaration): Boolean {
        val resolved = node.resolveBinding()
        visitFieldType(resolved, node.type)
        visitFieldDecl(resolved, node.name)
        return true
    }

    override fun visit(node: EnumConstantDeclaration): Boolean {
        visitFieldDecl(node.resolveVariable(), node.name)
        return true
    }

    private fun visitFieldDecl(resolved: IVariableBinding?, name: SimpleName) {
        if (resolved != null && resolved.isField) {
            val binding = Bindings.toString(resolved)
            if (binding != null) {
                annotatedSourceFile.annotate(name, BindingDecl(
                        binding,
                        parent = parentToString(resolved.declaringClass),
                        description = BindingDecl.Description.Field(
                                name = resolved.name,
                                typeBinding = describeType(resolved.type)
                        ),
                        modifiers = getModifiers(resolved)
                ))
            }
        }
    }

    override fun visit(node: VariableDeclarationExpression): Boolean {
        val resolved = (node.fragments()[0] as VariableDeclarationFragment).resolveBinding()
        visitFieldType(resolved, node.type)
        return true
    }

    override fun visit(node: VariableDeclarationStatement): Boolean {
        val resolved = (node.fragments()[0] as VariableDeclarationFragment).resolveBinding()
        visitFieldType(resolved, node.type)
        return true
    }

    override fun visit(node: FieldDeclaration): Boolean {
        val resolved = (node.fragments()[0] as VariableDeclarationFragment).resolveBinding()
        visitFieldType(resolved, node.type)
        return true
    }

    private fun visitFieldType(binding: IVariableBinding?, type: Type) {
        if (binding == null) return
        visitType0(type,
                if (binding.isField) BindingRefType.FIELD_TYPE
                else if (binding.isParameter) BindingRefType.PARAMETER_TYPE
                else BindingRefType.LOCAL_VARIABLE_TYPE)
    }

    override fun visit(node: NameQualifiedType): Boolean {
        visitType(node)
        return false
    }

    override fun visit(node: SimpleType): Boolean {
        visitType(node)
        return false
    }

    override fun visit(node: ArrayType): Boolean {
        visitType(node)
        return false
    }

    override fun visit(node: WildcardType): Boolean {
        visitType(node)
        return false
    }

    override fun visit(node: IntersectionType): Boolean {
        visitType(node)
        return false
    }

    override fun visit(node: UnionType): Boolean {
        visitType(node)
        return false
    }

    override fun visit(node: ParameterizedType): Boolean {
        visitType(node)
        return false
    }

    private fun visitType(node: Type) {
        val parent = node.parent
        if (parent !is AbstractTypeDeclaration &&
                node.locationInParent != MethodDeclaration.RETURN_TYPE2_PROPERTY &&
                node.locationInParent != SingleVariableDeclaration.TYPE_PROPERTY &&
                node.locationInParent != VariableDeclarationExpression.TYPE_PROPERTY &&
                node.locationInParent != VariableDeclarationStatement.TYPE_PROPERTY &&
                node.locationInParent != InstanceofExpression.RIGHT_OPERAND_PROPERTY &&
                node.locationInParent != FieldDeclaration.TYPE_PROPERTY &&
                node.locationInParent != CastExpression.TYPE_PROPERTY &&
                node.locationInParent != ClassInstanceCreation.TYPE_PROPERTY &&
                node.locationInParent != MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY &&
                node.locationInParent != CreationReference.TYPE_PROPERTY &&
                node.locationInParent != TypeMethodReference.TYPE_PROPERTY &&
                node.locationInParent != AnnotationTypeMemberDeclaration.TYPE_PROPERTY &&
                node.locationInParent != MethodInvocation.TYPE_ARGUMENTS_PROPERTY &&
                node.locationInParent != QualifiedType.QUALIFIER_PROPERTY &&
                node.locationInParent != CreationReference.TYPE_ARGUMENTS_PROPERTY &&
                node.locationInParent != ExpressionMethodReference.TYPE_ARGUMENTS_PROPERTY &&
                node.locationInParent != SuperMethodReference.TYPE_ARGUMENTS_PROPERTY &&
                node.locationInParent != TypeMethodReference.TYPE_ARGUMENTS_PROPERTY &&
                node.locationInParent != SuperMethodInvocation.TYPE_ARGUMENTS_PROPERTY) {

            visitType0(node, BindingRefType.UNCLASSIFIED)
        }
    }

    private fun visitType0(node: Type, refType: BindingRefType) {
        if (node is ParameterizedType) {
            visitType0(node.type, refType)
            for (typeArgument in node.typeArguments()) {
                visitType0(typeArgument as Type, BindingRefType.TYPE_PARAMETER)
            }
            return
        }
        if (node is WildcardType) {
            val bound = node.bound
            if (bound != null) {
                return visitType0(bound, BindingRefType.WILDCARD_BOUND)
            }
        }
        if (node is IntersectionType) {
            for (type in node.types()) {
                visitType0(type as Type, refType)
            }
            return
        }
        if (node is UnionType) {
            for (type in node.types()) {
                visitType0(type as Type, refType)
            }
            return
        }
        if (node is ArrayType) {
            visitType0(node.elementType, BindingRefType.TYPE_PARAMETER)
            return
        }
        if (node is QualifiedType) {
            val qualifier = node.qualifier.resolveBinding()
            if (qualifier != null) {
                visitType0(node.qualifier, BindingRefType.NESTED_CLASS_QUALIFIER)
                visitName0(node.name, refType)
                return
            }
        }

        val binding = node.resolveBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)
            if (s != null) annotatedSourceFile.annotate(node, makeBindingRef(refType, s))
        }
    }

    override fun visit(node: MethodInvocation): Boolean {
        val binding = node.resolveMethodBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)
            if (s != null) annotatedSourceFile.annotate(node.name,
                    makeBindingRef(BindingRefType.METHOD_CALL, s))
        }
        val expr = node.expression
        if (expr is Name && expr.resolveBinding() is ITypeBinding) {
            visitName0(expr, BindingRefType.STATIC_MEMBER_QUALIFIER)
        }
        for (typeArgument in node.typeArguments()) {
            visitType0(typeArgument as Type, BindingRefType.TYPE_PARAMETER)
        }
        return true
    }

    override fun visit(node: SuperMethodInvocation): Boolean {
        val binding = node.resolveMethodBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)
            if (s != null) annotatedSourceFile.annotate(node.name,
                    makeBindingRef(BindingRefType.SUPER_METHOD_CALL, s))
        }
        if (node.qualifier != null) {
            visitName0(node.qualifier, BindingRefType.SUPER_REFERENCE_QUALIFIER)
        }
        for (typeArgument in node.typeArguments()) {
            visitType0(typeArgument as Type, BindingRefType.TYPE_PARAMETER)
        }
        return true
    }

    override fun visit(node: ClassInstanceCreation): Boolean {
        val binding = node.resolveConstructorBinding()
        if (binding != null) {
            val declaringClass = binding.declaringClass
            if (binding.isDefaultConstructor) {
                if (declaringClass.isAnonymous) {
                    if (declaringClass.superclass != null) {
                        val constructor = declaringClass.superclass.declaredMethods.firstOrNull {
                            binding.overrides(it) && !it.isDefaultConstructor
                        }
                        if (constructor != null) {
                            // ref the constructor that is overridden
                            val s = Bindings.toString(constructor)
                            if (s != null) annotatedSourceFile.annotate(node.type,
                                    makeBindingRef(BindingRefType.CONSTRUCTOR_CALL, s))
                        } else {
                            // no constructor found (maybe default constructor), instead ref the superclass
                            val s = Bindings.toString(declaringClass.superclass)
                            if (s != null) annotatedSourceFile.annotate(node.type,
                                    makeBindingRef(BindingRefType.CONSTRUCTOR_CALL, s))
                        }
                    }
                } else {
                    val s = Bindings.toString(declaringClass)
                    if (s != null) annotatedSourceFile.annotate(node.type,
                            makeBindingRef(BindingRefType.CONSTRUCTOR_CALL, s))
                }
            } else {
                val s = Bindings.toString(binding)
                if (s != null) annotatedSourceFile.annotate(node.type,
                        makeBindingRef(BindingRefType.CONSTRUCTOR_CALL, s))
            }
        }
        return true
    }

    override fun visit(node: AnonymousClassDeclaration): Boolean {
        val resolved = node.resolveBinding()
        if (resolved != null && !resolved.isEnum) {
            val superclass = resolved.superclass?.let { Bindings.toString(it) }
            if (superclass != null) annotatedSourceFile.annotate(node.startPosition, 0,
                    makeBindingRef(BindingRefType.SUPER_TYPE, superclass))
            visitTypeDecl(
                    resolved,
                    nameStartPosition = node.startPosition,
                    declAnnotationLength = 0,
                    interfaceTypes = null,
                    superclassType = null // handled above
            )
        }
        return true
    }

    override fun visit(node: ConstructorInvocation): Boolean {
        val binding = node.resolveConstructorBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)
            if (s != null) {
                val lastTypeArg = node.typeArguments()?.lastOrNull() as? Type
                val scanStart = if (lastTypeArg == null) node.startPosition
                else lastTypeArg.startPosition + lastTypeArg.length
                val keywordPos = findToken("this", scanStart)
                annotatedSourceFile.annotate(keywordPos, 4,
                        makeBindingRef(BindingRefType.CONSTRUCTOR_CALL, s))
            }
        }
        return true
    }

    override fun visit(node: SuperConstructorInvocation): Boolean {
        val superKeywordStart =
                if (node.expression == null) node.startPosition
                else node.expression.startPosition + node.expression.length + 1
        val binding = node.resolveConstructorBinding()
        if (binding != null && binding.declaringClass != null) {
            val s = Bindings.toString(binding)
            if (s != null) {
                annotatedSourceFile.annotate(superKeywordStart, "super".length,
                        makeBindingRef(BindingRefType.SUPER_CONSTRUCTOR_CALL, s))
            }
        }
        return true
    }

    private fun fieldAccessTypeForNode(node: ASTNode) =
            when {
                node.locationInParent == Assignment.LEFT_HAND_SIDE_PROPERTY -> {
                    if ((node.parent as Assignment).operator == Assignment.Operator.ASSIGN) {
                        BindingRefType.FIELD_WRITE
                    } else {
                        BindingRefType.FIELD_READ_WRITE
                    }
                }
                node.locationInParent == PostfixExpression.OPERAND_PROPERTY -> BindingRefType.FIELD_READ_WRITE
                node.locationInParent == PrefixExpression.OPERAND_PROPERTY -> BindingRefType.FIELD_READ_WRITE
                else -> BindingRefType.FIELD_READ
            }

    override fun visit(node: FieldAccess): Boolean {
        val binding = node.resolveFieldBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)
            if (s != null)
                annotatedSourceFile.annotate(node.name, makeBindingRef(fieldAccessTypeForNode(node), s))
        }
        val expr = node.expression
        if (expr is Name && expr.resolveBinding() is ITypeBinding) {
            visitName0(expr, BindingRefType.STATIC_MEMBER_QUALIFIER)
        }
        return true
    }

    override fun visit(node: SuperFieldAccess): Boolean {
        val binding = node.resolveFieldBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)
            if (s != null)
                annotatedSourceFile.annotate(node.name, makeBindingRef(fieldAccessTypeForNode(node), s))
        }
        if (node.qualifier != null) {
            visitName0(node.qualifier, BindingRefType.SUPER_REFERENCE_QUALIFIER)
        }
        return true
    }

    override fun visit(node: SimpleName): Boolean {
        visitNameNode(node)
        return false
    }

    override fun visit(node: QualifiedName): Boolean {
        visitNameNode(node)
        return false
    }

    private fun visitNameNode(node: Name) {
        val parent = node.parent
        if (node.locationInParent != MethodInvocation.NAME_PROPERTY &&
                node.locationInParent != SuperMethodInvocation.NAME_PROPERTY &&
                (parent !is AbstractTypeDeclaration || node != parent.name) &&
                node.locationInParent != MethodDeclaration.NAME_PROPERTY &&
                (node.locationInParent != VariableDeclarationFragment.NAME_PROPERTY ||
                        (parent as VariableDeclarationFragment).resolveBinding()?.isField == false) &&
                (parent !is Annotation || node != parent.typeName) &&
                parent !is Type &&
                (node.locationInParent != MethodInvocation.EXPRESSION_PROPERTY ||
                        node.resolveBinding() !is ITypeBinding) &&
                node.locationInParent != FieldAccess.NAME_PROPERTY &&
                node.locationInParent != ExpressionMethodReference.EXPRESSION_PROPERTY &&
                node.locationInParent != ExpressionMethodReference.NAME_PROPERTY &&
                node.locationInParent != TypeMethodReference.NAME_PROPERTY &&
                node.locationInParent != SuperMethodReference.NAME_PROPERTY &&
                node.locationInParent != SuperFieldAccess.NAME_PROPERTY &&
                node.locationInParent != AnnotationTypeMemberDeclaration.NAME_PROPERTY &&
                node.locationInParent != SuperFieldAccess.QUALIFIER_PROPERTY &&
                node.locationInParent != SuperMethodInvocation.QUALIFIER_PROPERTY &&
                node.locationInParent != SuperMethodReference.QUALIFIER_PROPERTY &&
                node.locationInParent != ThisExpression.QUALIFIER_PROPERTY &&
                node.locationInParent != MemberValuePair.NAME_PROPERTY) {
            visitName0(node, null)
        }
    }

    private fun visitName0(node: Name, refType: BindingRefType?) {
        val binding = node.resolveBinding()
        if (binding is IVariableBinding) {
            val target = if (node is QualifiedName) node.name else node
            if (binding.isField) {
                val s = Bindings.toString(binding)
                if (s != null) annotatedSourceFile.annotate(target,
                        makeBindingRef(refType ?: fieldAccessTypeForNode(node), s))
            } else { // local
                val id = Long.toHexString(Hashing.goodFastHash(64)
                        .hashString(binding.key, Charsets.UTF_8).asLong())
                annotatedSourceFile.annotate(target, LocalVariableRef(id))
            }

            if (node is QualifiedName) {
                val qualifier = node.qualifier
                if (qualifier.resolveBinding() is ITypeBinding) {
                    visitName0(qualifier, BindingRefType.STATIC_MEMBER_QUALIFIER)
                }
            }
        }
        if (binding is ITypeBinding) {
            val s = Bindings.toString(binding)
            if (s != null) annotatedSourceFile.annotate(node, makeBindingRef(refType ?: BindingRefType.UNCLASSIFIED, s))
        }
        if (binding is IMethodBinding) {
            val s = Bindings.toString(binding)
            if (s != null) annotatedSourceFile.annotate(node, makeBindingRef(refType ?: BindingRefType.UNCLASSIFIED, s))
        }
    }

    override fun visit(node: MethodRef): Boolean {
        val binding = node.resolveBinding()
        if (binding is IMethodBinding) {
            val s = Bindings.toString(binding)
            if (s != null) {
                annotatedSourceFile.annotate(node.name, makeBindingRef(BindingRefType.JAVADOC, s))
            }
        }
        return false
    }

    override fun visit(node: MemberRef): Boolean {
        val binding = node.resolveBinding()
        if (binding is IMethodBinding) {
            val s = Bindings.toString(binding)
            if (s != null) annotatedSourceFile.annotate(node.name,
                    makeBindingRef(BindingRefType.JAVADOC, s))
        } else if (binding is IVariableBinding && binding.isField) {
            val s = Bindings.toString(binding)
            if (s != null) annotatedSourceFile.annotate(node.name,
                    makeBindingRef(BindingRefType.JAVADOC, s))
        }
        return false
    }

    override fun visit(node: InstanceofExpression): Boolean {
        visitType0(node.rightOperand, BindingRefType.INSTANCE_OF)
        return true
    }

    override fun visit(node: ImportDeclaration): Boolean {
        visitName0(node.name, BindingRefType.IMPORT)
        return false
    }

    override fun visit(node: CastExpression): Boolean {
        visitType0(node.type, BindingRefType.CAST)
        return true
    }

    override fun visit(node: MarkerAnnotation) = visitAnnotation(node)
    override fun visit(node: SingleMemberAnnotation) = visitAnnotation(node)
    override fun visit(node: NormalAnnotation) = visitAnnotation(node)

    private fun visitAnnotation(annotation: Annotation): Boolean {
        visitName0(annotation.typeName, BindingRefType.ANNOTATION_TYPE)
        return true
    }

    override fun visit(node: MemberValuePair): Boolean {
        visitName0(node.name, BindingRefType.ANNOTATION_MEMBER_VALUE)
        return true
    }

    override fun visit(node: Javadoc): Boolean {
        this.inJavadoc = true
        return true
    }

    override fun visit(node: LambdaExpression): Boolean {
        val binding = node.resolveMethodBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)
            if (s != null) {
                val internal = unsafeGetInternalNode(node) as org.eclipse.jdt.internal.compiler.ast.LambdaExpression

                annotatedSourceFile.annotate(internal.arrowPosition - 1, 2,
                        makeBindingRef(BindingRefType.SUPER_METHOD, s))
            }

            annotatedSourceFile.annotate(node.startPosition, 0, BindingDecl(
                    Bindings.toStringKeyBinding(binding),
                    parent = parentToString(binding.declaringMember),
                    description = BindingDecl.Description.Lambda(
                            implementingMethodBinding = describeMethod(binding),
                            implementingTypeBinding = describeType(binding.declaringClass)
                    ),
                    modifiers = BindingDecl.MODIFIER_ANONYMOUS or BindingDecl.MODIFIER_LOCAL
            ))
        }
        val typeBinding = node.resolveTypeBinding()
        if (typeBinding != null) {
            val s = Bindings.toString(typeBinding)
            // 0-length reference, we don't usually care about the supertype
            if (s != null) annotatedSourceFile.annotate(node.startPosition, 0,
                    makeBindingRef(BindingRefType.SUPER_TYPE, s))
        }

        @Suppress("UNCHECKED_CAST")
        for (parameter in node.parameters() as List<VariableDeclaration>) {
            if (parameter is VariableDeclarationFragment) {
                val paramBinding = parameter.resolveBinding()?.type
                if (paramBinding != null) {
                    val s = Bindings.toString(paramBinding)
                    if (s != null) annotatedSourceFile.annotate(parameter.name,
                            makeBindingRef(BindingRefType.PARAMETER_TYPE, s))
                }
            }
        }

        return true
    }

    private fun visitMethodReference(node: MethodReference) {
        val internal = unsafeGetInternalNode(node) as ReferenceExpression

        val binding = node.resolveMethodBinding()
        if (binding != null) {
            val s = Bindings.toString(binding)

            if (s != null) annotatedSourceFile.annotate(internal.nameSourceStart,
                    internal.sourceEnd - internal.nameSourceStart + 1,
                    makeBindingRef(BindingRefType.METHOD_CALL, s))
        }
        val typeBinding = node.resolveTypeBinding()
        if (typeBinding != null) {
            val s = Bindings.toString(typeBinding)
            // leave an anchor but no link
            if (s != null) annotatedSourceFile.annotate(node.startPosition, 0,
                    makeBindingRef(BindingRefType.SUPER_TYPE, s))
        }

        val superBinding = internal.descriptor
        if (superBinding != null) {
            val methodBinding = unsafeGetMethodBinding(node.ast, superBinding)
            if (methodBinding != null) {
                val s = Bindings.toString(methodBinding)
                // annotate the ::
                if (s != null) annotatedSourceFile.annotate(internal.lhs.sourceEnd + 1, 2,
                        makeBindingRef(BindingRefType.SUPER_METHOD, s))
            }
        }

        for (typeArgument in node.typeArguments()) {
            visitType0(typeArgument as Type, BindingRefType.TYPE_PARAMETER)
        }
    }

    override fun visit(node: ExpressionMethodReference): Boolean {
        val typeBinding = (node.expression as? Name)?.resolveBinding() as? ITypeBinding
        if (typeBinding != null) {
            val s = Bindings.toString(typeBinding)
            if (s != null) annotatedSourceFile.annotate(node.expression,
                    makeBindingRef(BindingRefType.METHOD_REFERENCE_RECEIVER_TYPE, s))
        }
        visitMethodReference(node)
        return true
    }

    override fun visit(node: CreationReference): Boolean {
        val typeBinding = node.type.resolveBinding()
        if (typeBinding != null) {
            val s = Bindings.toString(typeBinding)
            if (s != null) annotatedSourceFile.annotate(node.type,
                    makeBindingRef(BindingRefType.METHOD_REFERENCE_RECEIVER_TYPE, s))
        }
        visitMethodReference(node)
        return true
    }

    override fun visit(node: SuperMethodReference): Boolean {
        visitMethodReference(node)
        if (node.qualifier != null) {
            visitName0(node.qualifier, BindingRefType.SUPER_REFERENCE_QUALIFIER)
        }
        return true
    }

    override fun visit(node: TypeMethodReference): Boolean {
        val typeBinding = node.type.resolveBinding()
        if (typeBinding != null) {
            val s = Bindings.toString(typeBinding)
            if (s != null) annotatedSourceFile.annotate(node.type,
                    makeBindingRef(BindingRefType.METHOD_REFERENCE_RECEIVER_TYPE, s))
        }
        visitMethodReference(node)
        return true
    }

    override fun visit(node: TypeLiteral): Boolean {
        // X.class is technically not a static member, but who cares.
        visitType0(node.type, BindingRefType.STATIC_MEMBER_QUALIFIER)
        return false
    }

    override fun visit(node: ThisExpression): Boolean {
        if (node.qualifier != null) {
            visitName0(node.qualifier, BindingRefType.THIS_REFERENCE_QUALIFIER)
        }
        return true
    }

    override fun endVisit(node: Javadoc) {
        this.inJavadoc = false
    }

    private fun findToken(token: String, start: Int): Int {
        var pos = start
        while (true) {
            pos = annotatedSourceFile.text.indexOf(token, pos)
            val comment = ast.commentList.find {
                it is Comment && it.startPosition <= pos && it.startPosition + it.length > pos
            }
            if (comment != null) {
                pos = (comment as Comment).startPosition + comment.length
            } else {
                break
            }
        }
        return pos
    }

    private fun unsafeGetInternalNode(astNode: ASTNode): org.eclipse.jdt.internal.compiler.ast.ASTNode {
        return unsafeBindingResolverCall(astNode.ast, "getCorrespondingNode", astNode)
                as org.eclipse.jdt.internal.compiler.ast.ASTNode
    }

    private fun unsafeGetMethodBinding(ast: AST, internalBinding: MethodBinding): IMethodBinding? {
        return unsafeBindingResolverCall(ast, "getMethodBinding", internalBinding) as IMethodBinding?
    }

    private inline fun <reified T> unsafeBindingResolverCall(ast: AST, name: String, param: T): Any? {
        val classDefaultBindingResolver = Class.forName("org.eclipse.jdt.core.dom.DefaultBindingResolver")
        val getBindingResolver = AST::class.java.getDeclaredMethod("getBindingResolver")
        getBindingResolver.isAccessible = true
        val bindingResolver = getBindingResolver.invoke(ast)
        val getCorrespondingNode = classDefaultBindingResolver.getDeclaredMethod(name, T::class.java)
        getCorrespondingNode.isAccessible = true
        return getCorrespondingNode.invoke(bindingResolver, param)
    }

    private fun getModifiers(binding: IBinding): Int {
        var modifiers = binding.modifiers
        if (binding.isDeprecated) {
            modifiers = modifiers or BindingDecl.MODIFIER_DEPRECATED
        }
        if (binding is ITypeBinding) {
            if (binding.isLocal) {
                modifiers = modifiers or BindingDecl.MODIFIER_LOCAL
            }
            if (binding.isAnonymous) {
                modifiers = modifiers or BindingDecl.MODIFIER_ANONYMOUS
            }
        }
        return modifiers
    }

    private fun parentToString(parent: IBinding?): String? {
        return when (parent) {
            null -> null
            is IVariableBinding -> Bindings.toString(parent)
            is IMethodBinding -> {
                when {
                    // initializer block
                    //parent.name == "" -> Bindings.toStringKeyBinding(parent)
                    // we can't get the binding at the declaration site of the init block :(
                    parent.name == "" -> parentToString(parent.declaringClass)

                    // lambda
                    parent.declaringMember != null -> Bindings.toStringKeyBinding(parent)
                    else -> Bindings.toString(parent)
                }
            }
            is ITypeBinding -> {
                if (parent.isAnonymous && parent.isEnum) {
                    // skip the anonymous class and make the declaring field the parent
                    parentToString(parent.declaringMember)
                } else {
                    Bindings.toString(parent)
                }
            }
            else -> throw AssertionError()
        }
    }
}