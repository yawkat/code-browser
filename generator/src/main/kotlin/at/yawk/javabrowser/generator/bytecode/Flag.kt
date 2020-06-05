package at.yawk.javabrowser.generator.bytecode

import org.objectweb.asm.Opcodes
import java.util.EnumSet

data class Flag(
        val opcode: Int,
        val id: String,
        val modifier: String?,
        val targets: Set<Target>
) {
    private constructor(opcode: Int, id: String, modifier: String?, vararg targets: Target) :
            this(opcode, id, modifier, EnumSet.copyOf(targets.asList()))

    companion object {
        val FLAGS = listOf(
                Flag(Opcodes.ACC_PUBLIC, "ACC_PUBLIC", "public", Target.CLASS, Target.FIELD, Target.METHOD),
                Flag(Opcodes.ACC_PRIVATE, "ACC_PRIVATE", "private", Target.CLASS, Target.FIELD, Target.METHOD),
                Flag(Opcodes.ACC_PROTECTED, "ACC_PROTECTED", "protected", Target.CLASS, Target.FIELD, Target.METHOD),
                Flag(Opcodes.ACC_STATIC, "ACC_STATIC", "static", Target.FIELD, Target.METHOD),
                Flag(Opcodes.ACC_FINAL, "ACC_FINAL", "final", Target.CLASS, Target.FIELD, Target.METHOD, Target.PARAMETER),
                Flag(Opcodes.ACC_SUPER, "ACC_SUPER", null, Target.CLASS),
                Flag(Opcodes.ACC_SYNCHRONIZED, "ACC_SYNCHRONIZED", "synchronized", Target.METHOD),
                Flag(Opcodes.ACC_OPEN, "ACC_OPEN", "synchronized", Target.MODULE),
                Flag(Opcodes.ACC_TRANSITIVE, "ACC_TRANSITIVE", "transitive", Target.REQUIRES),
                Flag(Opcodes.ACC_VOLATILE, "ACC_VOLATILE", "volatile", Target.FIELD),
                Flag(Opcodes.ACC_BRIDGE, "ACC_BRIDGE", null, Target.METHOD),
                Flag(Opcodes.ACC_STATIC_PHASE, "ACC_STATIC_PHASE", "static", Target.REQUIRES),
                Flag(Opcodes.ACC_VARARGS, "ACC_VARARGS", null, Target.METHOD),
                Flag(Opcodes.ACC_TRANSIENT, "ACC_TRANSIENT", "transient", Target.FIELD),
                Flag(Opcodes.ACC_NATIVE, "ACC_NATIVE", "native", Target.METHOD),
                Flag(Opcodes.ACC_INTERFACE, "ACC_INTERFACE", null, Target.CLASS),
                Flag(Opcodes.ACC_ABSTRACT, "ACC_ABSTRACT", "abstract", Target.CLASS, Target.METHOD),
                Flag(Opcodes.ACC_STRICT, "ACC_STRICT", "strict", Target.METHOD),
                Flag(Opcodes.ACC_SYNTHETIC, "ACC_SYNTHETIC", null, EnumSet.allOf(Target::class.java)),
                Flag(Opcodes.ACC_ANNOTATION, "ACC_ANNOTATION", null, Target.CLASS),
                Flag(Opcodes.ACC_ENUM, "ACC_ANNOTATION", null, Target.CLASS, Target.FIELD),
                Flag(Opcodes.ACC_MANDATED, "ACC_MANDATED", null, EnumSet.allOf(Target::class.java)),
                Flag(Opcodes.ACC_MODULE, "ACC_MODULE", null, Target.CLASS)
        )
    }

    enum class Target {
        CLASS, FIELD, METHOD, PARAMETER, MODULE, REQUIRES
    }
}