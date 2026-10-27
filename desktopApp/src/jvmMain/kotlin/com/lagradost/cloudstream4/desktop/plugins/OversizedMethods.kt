package com.lagradost.cloudstream4.desktop.plugins

import com.lagradost.cloudstream4.desktop.data.AppLog
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * dex2jar turns the register moves of dex code into `load a; store b` pairs, which can push a big Kotlin
 * method (a coroutine with many branches) over the JVM limit of 64 KB of bytecode. The JVM only checks that
 * limit while verifying, which is turned off for extensions, and the ASM bundled with dex2jar writes such
 * methods anyway, with broken jumps, so the app crashed the first time the JVM looked at one.
 *
 * Such a method is split into smaller ones by [MethodSplitter]. One that cannot be split throws when called
 * instead, extensions run each source on their own and skip one that fails, so only that part stops working.
 */
object OversizedMethods {
    private const val MAX_CODE_SIZE = 65535

    /** A conditional jump further than 32 KB becomes the opposite jump over a `goto_w` */
    private const val WIDENED_JUMP_BYTES = 5

    /** Fixes the methods of a class before it is written */
    fun fix(node: ClassNode) {
        for (method in tooLargeMethods(node)) {
            if (method.name == "<init>") continue
            val splitter = MethodSplitter(node, method)
            var parts = 0
            val split = runCatching {
                while (method in tooLargeMethods(node)) {
                    if (!splitter.splitOnce()) return@runCatching false
                    parts++
                }
                true
            }.onFailure { AppLog.log("Could not split extension method ${node.name}.${method.name}: $it") }.getOrDefault(false)
            if (split) {
                AppLog.log("Split extension method ${node.name}.${method.name} into ${parts + 1} parts, it was too large for Java")
            } else {
                // Possibly half split, so it cannot be trusted
                AppLog.log("Extension method ${node.name}.${method.name} is too large for Java, it will not work")
                replaceWithThrow(method)
            }
        }
        for (method in tooLargeMethods(node)) {
            AppLog.log("Extension method ${node.name}.${method.name} is too large for Java, it will not work")
            replaceWithThrow(method)
        }
    }

    private const val TOO_LARGE_MESSAGE = "This part of the extension is too large to run on desktop"

    /**
     * Whether a class already written has to be converted again: it has a method over the limit, which cannot be
     * repaired since its jumps are broken, or one an older version replaced since it could not split it yet.
     */
    fun needsConversion(bytes: ByteArray) = hasTooLargeMethod(bytes) || String(bytes, Charsets.ISO_8859_1).contains(TOO_LARGE_MESSAGE)

    private fun hasTooLargeMethod(bytes: ByteArray): Boolean {
        if (bytes.size <= MAX_CODE_SIZE) return false
        val reader = ClassReader(bytes)
        val buffer = CharArray(reader.maxStringLength)
        // Access flags, this class and super class, then the interfaces
        var offset = reader.header + 6
        offset += 2 + 2 * reader.readUnsignedShort(offset)
        var tooLarge = false
        // Fields, then methods: access, name, descriptor, then attributes
        repeat(2) { isMethods ->
            repeat(reader.readUnsignedShort(offset).also { offset += 2 }) {
                repeat(reader.readUnsignedShort(offset + 6).also { offset += 8 }) {
                    val name = reader.readUTF8(offset, buffer)
                    // Code: max stack, max locals, then the code length
                    if (isMethods == 1 && name == "Code" && reader.readInt(offset + 10) > MAX_CODE_SIZE) tooLarge = true
                    offset += 6 + reader.readInt(offset + 2)
                }
            }
        }
        return tooLarge
    }

    /**
     * Sizes come from a label after the last instruction. Long jumps are widened after that, so the worst
     * case of every jump is added.
     */
    private fun tooLargeMethods(node: ClassNode): List<MethodNode> {
        val ends = node.methods.filter { it.instructions.size() > 0 }.associateWith { LabelNode().also(it.instructions::add) }
        node.accept(ClassWriter(0))
        ends.forEach { (method, end) -> method.instructions.remove(end) }
        return ends.filter { (method, end) ->
            end.label.offset + WIDENED_JUMP_BYTES * method.instructions.count { it is JumpInsnNode } > MAX_CODE_SIZE
        }.keys.toList()
    }

    private fun replaceWithThrow(method: MethodNode) {
        method.instructions = InsnList().apply {
            add(TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"))
            add(InsnNode(Opcodes.DUP))
            add(LdcInsnNode(TOO_LARGE_MESSAGE))
            add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V"))
            add(InsnNode(Opcodes.ATHROW))
        }
        method.tryCatchBlocks.clear()
        method.localVariables = null
    }
}
