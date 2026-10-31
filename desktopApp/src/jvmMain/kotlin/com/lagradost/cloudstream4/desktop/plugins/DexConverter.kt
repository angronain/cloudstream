package com.lagradost.cloudstream4.desktop.plugins

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.Dex2Asm.ClzCtx
import com.googlecode.d2j.converter.IR2JConverter
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.dex.ExDex2Asm
import com.googlecode.d2j.dex.LambadaNameSafeClassAdapter
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.dex2jar.ir.ET
import com.googlecode.dex2jar.ir.IrMethod
import com.googlecode.dex2jar.ir.expr.Local
import com.googlecode.dex2jar.ir.expr.RefExpr
import com.googlecode.dex2jar.ir.expr.Value
import com.googlecode.dex2jar.ir.stmt.BaseSwitchStmt
import com.googlecode.dex2jar.ir.stmt.JumpStmt
import com.googlecode.dex2jar.ir.stmt.LabelStmt
import com.googlecode.dex2jar.ir.stmt.Stmt
import com.googlecode.dex2jar.ir.ts.AggTransformer
import com.googlecode.dex2jar.ir.ts.CleanLabel
import com.googlecode.dex2jar.ir.ts.DeadCodeTransformer
import com.googlecode.dex2jar.ir.ts.ExceptionHandlerTrim
import com.googlecode.dex2jar.ir.ts.Ir2JRegAssignTransformer
import com.googlecode.dex2jar.ir.ts.MultiArrayTransformer
import com.googlecode.dex2jar.ir.ts.NewTransformer
import com.googlecode.dex2jar.ir.ts.NpeTransformer
import com.googlecode.dex2jar.ir.ts.RemoveConstantFromSSA
import com.googlecode.dex2jar.ir.ts.RemoveLocalFromSSA
import com.googlecode.dex2jar.ir.ts.TypeTransformer
import com.googlecode.dex2jar.ir.ts.UnSSATransformer
import com.googlecode.dex2jar.ir.ts.VoidInvokeTransformer
import com.googlecode.dex2jar.ir.ts.ZeroTransformer
import com.googlecode.dex2jar.ir.ts.array.FillArrayTransformer
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.AppLog
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.BitSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns an Android extension (.cs3, a zip with classes.dex and manifest.json) into a JVM jar.
 */
object DexConverter {
    /** Bump when the conversion changes, older conversions are then repaired on startup */
    const val VERSION = 4

    /** Conversions older than this cannot be repaired in place and are converted again from the original */
    const val RECONVERT_BELOW = 2

    /**
     * Applies the bytecode fixes of the current [VERSION] to a jar converted by an older version. Returns false,
     * leaving the jar as it is, when it has to be converted again from the original instead.
     */
    fun repairJar(jar: File): Boolean {
        val needsConversion = ZipFile(jar).use { zip ->
            zip.entries().asSequence().any { entry ->
                entry.name.endsWith(".class") && OversizedMethods.needsConversion(zip.getInputStream(entry).use { it.readBytes() })
            }
        }
        if (needsConversion) return false
        val temp = File.createTempFile("extension", ".jar", AppDirs.cacheDir)
        try {
            ZipOutputStream(temp.outputStream()).use { out ->
                ZipFile(jar).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(if (entry.name.endsWith(".class")) fixLocalClasses(bytes) else bytes)
                        out.closeEntry()
                    }
                }
            }
            Files.move(temp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            temp.delete()
        }
    }

    /**
     * dex2jar turns local classes (classes declared inside a function) into member classes of the
     * outer class and drops their EnclosingMethod attribute. The JVM is fine with that, but Kotlin
     * reflection then looks for `Outer.Name`, which does not exist, so Jackson fails to parse into them.
     * A member class is always named `Outer$Name`, anything else is turned back into a local class.
     */
    private fun fixLocalClasses(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)
        var changed = false
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            private var className = ""
            private var hasOuterClass = false
            private var localOuter: String? = null

            override fun visit(
                version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?,
            ) {
                className = name
                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) {
                hasOuterClass = true
                super.visitOuterClass(owner, name, descriptor)
            }

            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                if (outerName != null && innerName != null && name != "$outerName$$innerName") {
                    changed = true
                    if (name == className) localOuter = outerName
                    super.visitInnerClass(name, null, innerName, access)
                } else {
                    super.visitInnerClass(name, outerName, innerName, access)
                }
            }

            override fun visitEnd() {
                // EnclosingMethod must come before InnerClasses in ASM's visit order, but ClassWriter
                // does not care about the order of attributes, so adding it here is fine
                localOuter?.takeIf { !hasOuterClass }?.let { super.visitOuterClass(it, null, null) }
                super.visitEnd()
            }
        }, 0)
        return if (changed) writer.toByteArray() else bytes
    }

    fun cs3ToJar(cs3: File): File {
        val jar = File.createTempFile("extension", ".jar", AppDirs.cacheDir)
        try {
            ZipOutputStream(jar.outputStream()).use { out ->
                val written = HashSet<String>()
                ZipFile(cs3).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                            translate(bytes) { name, classBytes ->
                                // A zip cannot hold the same class twice, a class in several dex files is kept once
                                if (written.add(name)) {
                                    out.putNextEntry(ZipEntry("$name.class"))
                                    out.write(classBytes)
                                    out.closeEntry()
                                }
                            }
                        } else if (entry.name == "manifest.json" || !entry.name.startsWith("res/")) {
                            // Keep the manifest and any plain java resources, Android resources are useless here
                            out.putNextEntry(ZipEntry(entry.name))
                            out.write(bytes)
                            out.closeEntry()
                        }
                    }
                }
            }
            return jar
        } catch (t: Throwable) {
            jar.delete()
            throw t
        }
    }

    /**
     * The same steps as `Dex2jar.to`, except that classes come out as ASM trees and are written here: the
     * ASM bundled with dex2jar writes broken jumps into methods over the JVM size limit, see [OversizedMethods].
     */
    private fun translate(dex: ByteArray, onClass: (name: String, bytes: ByteArray) -> Unit) {
        val dex2jar = Dex2jar.from(dex)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(false)
            .optimizeSynchronized(true)
            // Kotlin names like `constructor-impl` are valid on the JVM, renaming them breaks calls into Kotlin and the app
            .dontSanitizeNames(true)
        // The other options only matter to dex2jar's own converter, Converter below does what they ask
        val readerConfig = Dex2jar::class.java.getDeclaredField("readerConfig").apply { isAccessible = true }.getInt(dex2jar)
        val file = DexFileNode()
        try {
            dex2jar.reader.accept(file, readerConfig or DexFileReader.IGNORE_READ_EXCEPTION)
        } catch (e: Exception) {
            dex2jar.exceptionHandler.handleFileException(e)
        }
        val converter = Converter(dex2jar.exceptionHandler)
        converter.convertDex(file) {
            val node = object : ClassNode(Opcodes.ASM9) {
                override fun visitEnd() {
                    super.visitEnd()
                    try {
                        OversizedMethods.fix(this)
                        onClass(name, fixLocalClasses(ClassWriter(ClassWriter.COMPUTE_MAXS).also(::accept).toByteArray()))
                    } catch (e: Exception) {
                        // dex2jar skips a class it cannot write too
                        AppLog.log("Could not convert extension class $name: $e")
                    }
                }
            }
            LambadaNameSafeClassAdapter(node, true)
        }
    }
}

/**
 * dex2jar's own converter with the options [DexConverter] picks, except for how variables get their JVM slots
 * in very large methods. dex2jar shares slots between variables that are never alive at the same time, which
 * keeps every variable of every instruction in memory: a long Kotlin coroutine took 3.4 GB and minutes, more than
 * the app has. Those methods get [Converter.shareSlots] instead.
 */
private class Converter(handler: DexExceptionHandler?) : ExDex2Asm(handler) {
    override fun optimize(method: IrMethod) {
        CLEAN_LABEL.transform(method)
        DEAD_CODE.transform(method)
        REMOVE_LOCAL.transform(method)
        REMOVE_CONST.transform(method)
        ZERO.transform(method)
        if (NPE.transformReportChanged(method)) {
            DEAD_CODE.transform(method)
            REMOVE_LOCAL.transform(method)
            REMOVE_CONST.transform(method)
        }
        NEW.transform(method)
        FILL_ARRAY.transform(method)
        AGG.transform(method)
        MULTI_ARRAY.transform(method)
        VOID_INVOKE.transform(method)
        DEAD_CODE.transform(method)
        REMOVE_LOCAL.transform(method)
        REMOVE_CONST.transform(method)
        TYPE.transform(method)
        UNSSA.transform(method)
        if (method.stmts.getSize().toLong() * method.locals.size > LARGE_METHOD) shareSlots(method)
        else REG_ASSIGN.transform(method)
        TRIM_EX.transform(method)
    }

    override fun ir2j(method: IrMethod, mv: MethodVisitor, clzCtx: ClzCtx) {
        IR2JConverter().optimizeSynchronized(true).clzCtx(clzCtx).ir(method).asm(mv).convert()
    }

    /**
     * Shares slots between variables of one type that are never alive at the same time, like dex2jar, but works
     * out where each variable is alive per block of code, in a few bits each, instead of keeping a frame for every
     * instruction. @this and the parameters keep the slots the JVM passes them in.
     */
    private fun shareSlots(method: IrMethod) {
        val stmts = method.stmts.toList()
        val locals = method.locals
        val index = HashMap<Local, Int>(locals.size * 2)
        locals.forEachIndexed { i, local -> index[local] = i }

        fun forEachLocal(value: Value?, action: (Int) -> Unit) {
            if (value == null) return
            if (value is Local) {
                index[value]?.let(action)
                return
            }
            when (value.et) {
                ET.E1 -> forEachLocal(value.op, action)
                ET.E2 -> { forEachLocal(value.op1, action); forEachLocal(value.op2, action) }
                ET.En -> value.ops?.forEach { forEachLocal(it, action) }
                else -> {}
            }
        }
        fun defined(stmt: Stmt): Int? =
            if (stmt.st == Stmt.ST.ASSIGN || stmt.st == Stmt.ST.IDENTITY) (stmt.op1 as? Local)?.let { index[it] } else null
        fun forEachUse(stmt: Stmt, action: (Int) -> Unit) {
            if (defined(stmt) != null) return forEachLocal(stmt.op2, action)
            when (stmt.et) {
                ET.E1 -> forEachLocal(stmt.op, action)
                ET.E2 -> { forEachLocal(stmt.op1, action); forEachLocal(stmt.op2, action) }
                ET.En -> stmt.ops?.forEach { forEachLocal(it, action) }
                else -> {}
            }
        }

        // Blocks: a label starts one, a jump, switch, return or throw ends one
        val starts = ArrayList<Int>()
        val blockOf = IntArray(stmts.size)
        stmts.forEachIndexed { at, stmt ->
            val previous = stmts.getOrNull(at - 1)
            if (previous == null || stmt is LabelStmt || !previous.st.canContinue() || previous is JumpStmt || previous is BaseSwitchStmt) {
                starts += at
            }
            blockOf[at] = starts.size - 1
        }
        val count = starts.size
        fun end(block: Int) = if (block + 1 < count) starts[block + 1] - 1 else stmts.lastIndex
        val positions = HashMap<LabelStmt, Int>()
        stmts.forEachIndexed { at, stmt -> if (stmt is LabelStmt) positions[stmt] = at }
        fun blockAt(label: LabelStmt?) = positions[label]?.let { blockOf[it] }
        val successors = Array(count) { block ->
            val last = stmts[end(block)]
            val list = ArrayList<Int>(2)
            if (last.st.canContinue() && block + 1 < count) list += block + 1
            when (last) {
                is JumpStmt -> blockAt(last.target)?.let(list::add)
                is BaseSwitchStmt -> (last.targets.toList() + last.defaultTarget).forEach { target -> blockAt(target)?.let(list::add) }
                else -> {}
            }
            list.distinct().toIntArray()
        }
        // Any instruction of a try can go to its handlers, tries start and end on labels so on whole blocks
        val handlerLists = Array(count) { ArrayList<Int>(0) }
        for (trap in method.traps) {
            val start = blockAt(trap.start) ?: continue
            val end = blockAt(trap.end) ?: continue
            val handlers = trap.handlers.mapNotNull(::blockAt)
            for (block in start until end) handlerLists[block] += handlers
        }
        val handlers = Array(count) { handlerLists[it].distinct().toIntArray() }

        val uses = Array(count) { BitSet() }
        val defs = Array(count) { BitSet() }
        for (block in 0 until count) {
            for (at in starts[block]..end(block)) {
                forEachUse(stmts[at]) { if (!defs[block][it]) uses[block].set(it) }
                defined(stmts[at])?.let { defs[block].set(it) }
            }
        }
        val liveIn = Array(count) { BitSet() }
        val liveOut = Array(count) { BitSet() }
        do {
            var changed = false
            for (block in count - 1 downTo 0) {
                val out = BitSet()
                for (next in successors[block]) out.or(liveIn[next])
                // What a handler needs must survive the whole block, the block may stop anywhere
                for (handler in handlers[block]) out.or(liveIn[handler])
                val live = out.clone() as BitSet
                live.andNot(defs[block])
                live.or(uses[block])
                for (handler in handlers[block]) live.or(liveIn[handler])
                liveOut[block] = out
                if (live != liveIn[block]) {
                    liveIn[block] = live
                    changed = true
                }
            }
        } while (changed)

        // Which variables are alive together: walking each block backwards, a value written clashes with all alive then
        val clashes = Array(locals.size) { BitSet() }
        for (block in 0 until count) {
            val handlerLive = BitSet().apply { for (handler in handlers[block]) or(liveIn[handler]) }
            val live = liveOut[block].clone() as BitSet
            for (at in end(block) downTo starts[block]) {
                val stmt = stmts[at]
                defined(stmt)?.let { def ->
                    live.or(handlerLive)
                    live.stream().forEach { if (it != def) { clashes[def].set(it); clashes[it].set(def) } }
                    live.clear(def)
                }
                forEachUse(stmt) { live.set(it) }
            }
        }

        val fixed = HashMap<Local, Int>()
        var next = if (method.isStatic) 0 else 1
        val parameterSlots = IntArray(method.args.size) { i -> next.also { next += width(method.args[i]) } }
        for (stmt in stmts) {
            if (stmt.st != Stmt.ST.IDENTITY) continue
            val local = stmt.op1 as? Local ?: continue
            val ref = stmt.op2 as? RefExpr ?: continue
            when (ref.vt) {
                Value.VT.THIS_REF -> fixed[local] = 0
                Value.VT.PARAMETER_REF -> fixed[local] = parameterSlots[ref.parameterIndex]
                else -> {}
            }
        }

        fun kind(local: Local) = local.valueType[0].let { if (it == '[') 'L' else it }
        // A copy from one variable to another disappears when both share a slot: variables copied into each other
        // become one group when no two of them clash, the way dex2jar prefers it
        val group = IntArray(locals.size) { it }
        fun groupOf(i: Int): Int {
            var g = i
            while (group[g] != g) g = group[g]
            group[i] = g
            return g
        }
        val members = arrayOfNulls<BitSet>(locals.size)
        fun membersOf(g: Int) = members[g] ?: BitSet().apply { set(g) }.also { members[g] = it }
        val slotOf = HashMap<Int, Int>()
        fixed.forEach { (local, slot) -> slotOf[index.getValue(local)] = slot }
        for (stmt in stmts) {
            val to = defined(stmt) ?: continue
            val from = (stmt.op2 as? Local)?.let { index[it] } ?: continue
            if (kind(locals[to]) != kind(locals[from])) continue
            val a = groupOf(to)
            val b = groupOf(from)
            if (a == b || (a in slotOf && b in slotOf) || clashes[a].intersects(membersOf(b))) continue
            membersOf(a).or(membersOf(b))
            clashes[a].or(clashes[b])
            slotOf.remove(b)?.let { slotOf[a] = it }
            group[b] = a
        }

        // Colors per type, then each type gets slots of its own after the parameters: a slot only ever holds
        // one type, the JVM and the method splitter want it so
        val color = IntArray(locals.size) { -1 }
        val colors = HashMap<Char, Int>()
        val taken = BitSet()
        for (g in locals.indices) {
            if (groupOf(g) != g || g in slotOf) continue
            val kind = kind(locals[g])
            taken.clear()
            clashes[g].stream().forEach {
                val other = groupOf(it)
                if (color[other] >= 0 && kind(locals[other]) == kind) taken.set(color[other])
            }
            color[g] = taken.nextClearBit(0)
            colors[kind] = maxOf(colors[kind] ?: 0, color[g] + 1)
        }
        val base = HashMap<Char, Int>()
        for ((kind, used) in colors) {
            base[kind] = next
            next += used * width(kind.toString())
        }
        for (i in locals.indices) {
            val local = locals[i]
            val g = groupOf(i)
            local.lsIndex = slotOf[g] ?: (base.getValue(kind(local)) + color[g] * width(local.valueType))
        }
        for (stmt in stmts) stmt.frame = null
    }

    private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

    private companion object {
        /** Instructions times variables above which a method gets [shareSlots], about 1 GB in dex2jar */
        const val LARGE_METHOD = 20_000_000L

        val CLEAN_LABEL = CleanLabel()
        val DEAD_CODE = DeadCodeTransformer()
        val REMOVE_LOCAL = RemoveLocalFromSSA()
        val REMOVE_CONST = RemoveConstantFromSSA()
        val ZERO = ZeroTransformer()
        val NPE = NpeTransformer()
        val NEW = NewTransformer()
        val FILL_ARRAY = FillArrayTransformer()
        val AGG = AggTransformer()
        val MULTI_ARRAY = MultiArrayTransformer()
        val VOID_INVOKE = VoidInvokeTransformer()
        val TYPE = TypeTransformer()
        val UNSSA = UnSSATransformer()
        val REG_ASSIGN = Ir2JRegAssignTransformer()
        val TRIM_EX = ExceptionHandlerTrim()
    }
}
