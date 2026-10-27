package com.lagradost.cloudstream4.desktop.plugins

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicInterpreter
import java.util.BitSet

/**
 * Moves parts of a method too large for the JVM into methods of their own.
 *
 * A part is everything dominated by one instruction, so the only way into it is through that instruction,
 * where the stack is empty. The part becomes a static method: the locals it needs are passed in arrays, the
 * locals it changes come back the same way, and it returns which way it left. The original method then
 * continues there, returns the part's return value, or jumps to the exception handler with the exception
 * the part caught. Exceptions no handler of the original method catches just go through.
 */
internal class MethodSplitter(private val owner: ClassNode, private val method: MethodNode) {
    private var parts = 0

    /** Moves out one part, false when no part is worth it */
    fun splitOnce(): Boolean {
        val code = Code.analyze(owner, method) ?: return false
        val root = code.bestPart() ?: return false
        moveOut(code, root)
        return true
    }

    /** Instructions of [method] with everything needed to choose and move out a part */
    private class Code(
        val insns: Array<AbstractInsnNode>,
        /** Index in [insns] of each real instruction, labels and line numbers are not instructions */
        val real: IntArray,
        /** For each index in [insns], the first real instruction at or after it */
        val nextReal: IntArray,
        val successors: Array<IntArray>,
        val handlers: Array<IntArray>,
        val stackSizes: IntArray,
        val liveIn: Array<BitSet>,
        val idom: IntArray,
        val reversePostorder: IntArray,
        val kinds: Map<Int, Int>,
        val firstFreeSlot: Int,
    ) {
        val count get() = real.size

        /** Estimated bytes of everything each instruction dominates */
        val partSizes: LongArray by lazy {
            val sizes = LongArray(count)
            for (k in reversePostorder.indices.reversed()) {
                val r = reversePostorder[k]
                sizes[r] += estimatedSize(insns[real[r]]).toLong()
                if (r != 0) sizes[idom[r]] += sizes[r]
            }
            sizes
        }

        fun partOf(root: Int): BitSet {
            val children = Array(count) { mutableListOf<Int>() }
            for (r in reversePostorder) if (r != 0) children[idom[r]].add(r)
            val part = BitSet()
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val r = pending.removeLast()
                part.set(r)
                pending.addAll(children[r])
            }
            return part
        }

        fun bestPart(): Int? {
            val candidates = reversePostorder.filter { it != 0 && partSizes[it] in MIN_PART_SIZE..MAX_PART_SIZE }
                .sortedByDescending { partSizes[it] }
            for (root in candidates.take(MAX_CANDIDATES)) {
                if (stackSizes[root] != 0) continue
                val part = partOf(root)
                if (canMoveOut(part)) return root
            }
            return null
        }

        private fun canMoveOut(part: BitSet): Boolean {
            var r = part.nextSetBit(0)
            while (r >= 0) {
                val op = insns[real[r]].opcode
                if (op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT || op == Opcodes.JSR || op == Opcodes.RET) return false
                // The way out is a jump, so nothing can be left on the stack
                if (successors[r].any { !part[it] && stackSizes[it] != 0 }) return false
                r = part.nextSetBit(r + 1)
            }
            return true
        }

        companion object {
            fun analyze(owner: ClassNode, method: MethodNode): Code? {
                val insns = method.instructions.toArray()
                val kinds = localKinds(method, insns) ?: return null
                val firstFreeSlot = kinds.maxOfOrNull { (slot, kind) -> slot + if (kind == Type.LONG || kind == Type.DOUBLE) 2 else 1 } ?: 0

                val realList = ArrayList<Int>()
                val realId = IntArray(insns.size) { -1 }
                insns.forEachIndexed { i, insn -> if (insn.opcode >= 0) realId[i] = realList.size.also { realList.add(i) } }
                val real = realList.toIntArray()
                if (real.isEmpty()) return null
                val nextReal = IntArray(insns.size + 1) { -1 }
                for (i in insns.indices.reversed()) nextReal[i] = if (realId[i] >= 0) realId[i] else nextReal[i + 1]
                val labelIndex = HashMap<LabelNode, Int>()
                insns.forEachIndexed { i, insn -> if (insn is LabelNode) labelIndex[insn] = i }
                fun target(label: LabelNode) = nextReal[labelIndex.getValue(label)]

                val successors = Array(real.size) { r ->
                    val insn = insns[real[r]]
                    val fall = nextReal[real[r] + 1]
                    val list = when {
                        insn is JumpInsnNode -> listOf(target(insn.label)) + if (insn.opcode == Opcodes.GOTO) emptyList() else listOf(fall)
                        insn is TableSwitchInsnNode -> (insn.labels + insn.dflt).map(::target)
                        insn is LookupSwitchInsnNode -> (insn.labels + insn.dflt).map(::target)
                        insn.opcode in Opcodes.IRETURN..Opcodes.RETURN || insn.opcode == Opcodes.ATHROW -> emptyList()
                        else -> listOf(fall)
                    }
                    if (list.any { it < 0 }) return null
                    list.distinct().toIntArray()
                }
                val handlerLists = Array(real.size) { mutableListOf<Int>() }
                for (block in method.tryCatchBlocks) {
                    val handler = target(block.handler)
                    for (i in labelIndex.getValue(block.start) until labelIndex.getValue(block.end)) {
                        if (realId[i] >= 0) handlerLists[realId[i]].add(handler)
                    }
                }
                val handlers = Array(real.size) { handlerLists[it].distinct().toIntArray() }

                // Stack sizes, the analyzer needs room for every local and the deepest stack
                method.maxLocals = maxOf(method.maxLocals, firstFreeSlot)
                method.maxStack = maxOf(method.maxStack, ANALYZER_STACK)
                val frames = runCatching { Analyzer(BasicInterpreter()).analyze(owner.name, method) }.getOrNull() ?: return null
                val stackSizes = IntArray(real.size) { frames[real[it]]?.stackSize ?: -1 }

                val all = Array(real.size) { r -> successors[r] + handlers[r] }
                val reversePostorder = reversePostorder(all)
                val idom = dominators(all, reversePostorder)
                val liveIn = liveness(insns, real, all)
                return Code(insns, real, nextReal, successors, handlers, stackSizes, liveIn, idom, reversePostorder, kinds, firstFreeSlot)
            }

            /** The kind of value each local holds, null when a local holds different kinds, which cannot be passed in one array */
            private fun localKinds(method: MethodNode, insns: Array<AbstractInsnNode>): Map<Int, Int>? {
                val kinds = HashMap<Int, Int>()
                fun add(slot: Int, kind: Int): Boolean {
                    if (kinds.getOrPut(slot) { kind } != kind) return false
                    // The second half of a long or double is never used on its own
                    if (kind == Type.LONG || kind == Type.DOUBLE) return kinds[slot + 1] == null
                    return kinds[slot - 1].let { it != Type.LONG && it != Type.DOUBLE }
                }
                var slot = if (method.access and Opcodes.ACC_STATIC != 0) 0 else 1
                if (slot == 1) kinds[0] = Type.OBJECT
                for (type in Type.getArgumentTypes(method.desc)) {
                    if (!add(slot, kindOf(type))) return null
                    slot += type.size
                }
                for (insn in insns) {
                    val ok = when (insn) {
                        is VarInsnNode -> add(insn.`var`, kindOf(insn.opcode))
                        is IincInsnNode -> add(insn.`var`, Type.INT)
                        else -> true
                    }
                    if (!ok) return null
                }
                return kinds
            }

            private fun reversePostorder(successors: Array<IntArray>): IntArray {
                val post = ArrayList<Int>()
                val seen = BooleanArray(successors.size)
                val next = IntArray(successors.size)
                val stack = ArrayDeque(listOf(0))
                seen[0] = true
                while (stack.isNotEmpty()) {
                    val r = stack.last()
                    if (next[r] < successors[r].size) {
                        val s = successors[r][next[r]++]
                        if (!seen[s]) {
                            seen[s] = true
                            stack.addLast(s)
                        }
                    } else {
                        stack.removeLast()
                        post.add(r)
                    }
                }
                return post.reversed().toIntArray()
            }

            /** Cooper, Harvey and Kennedy's algorithm, unreachable instructions keep -1 */
            private fun dominators(successors: Array<IntArray>, reversePostorder: IntArray): IntArray {
                val order = IntArray(successors.size) { -1 }
                reversePostorder.forEachIndexed { k, r -> order[r] = k }
                val predecessors = Array(successors.size) { mutableListOf<Int>() }
                successors.forEachIndexed { r, list -> list.forEach { predecessors[it].add(r) } }
                val idom = IntArray(successors.size) { -1 }
                idom[0] = 0
                var changed = true
                while (changed) {
                    changed = false
                    for (k in 1 until reversePostorder.size) {
                        val r = reversePostorder[k]
                        var newIdom = -1
                        for (p in predecessors[r]) {
                            if (idom[p] < 0) continue
                            if (newIdom < 0) {
                                newIdom = p
                            } else {
                                var a = p
                                var b = newIdom
                                while (a != b) {
                                    while (order[a] > order[b]) a = idom[a]
                                    while (order[b] > order[a]) b = idom[b]
                                }
                                newIdom = a
                            }
                        }
                        if (idom[r] != newIdom) {
                            idom[r] = newIdom
                            changed = true
                        }
                    }
                }
                return idom
            }

            private fun liveness(insns: Array<AbstractInsnNode>, real: IntArray, successors: Array<IntArray>): Array<BitSet> {
                val liveIn = Array(real.size) { BitSet() }
                val predecessors = Array(real.size) { mutableListOf<Int>() }
                successors.forEachIndexed { r, list -> list.forEach { predecessors[it].add(r) } }
                val pending = ArrayDeque(real.indices.reversed().toList())
                val queued = BooleanArray(real.size) { true }
                while (pending.isNotEmpty()) {
                    val r = pending.removeFirst()
                    queued[r] = false
                    val live = BitSet()
                    for (s in successors[r]) live.or(liveIn[s])
                    when (val insn = insns[real[r]]) {
                        is VarInsnNode -> if (insn.opcode >= Opcodes.ISTORE) live.clear(insn.`var`) else live.set(insn.`var`)
                        is IincInsnNode -> live.set(insn.`var`)
                    }
                    if (live != liveIn[r]) {
                        liveIn[r] = live
                        for (p in predecessors[r]) if (!queued[p]) {
                            queued[p] = true
                            pending.addLast(p)
                        }
                    }
                }
                return liveIn
            }
        }
    }

    private fun moveOut(code: Code, root: Int) {
        val part = code.partOf(root)
        val insns = code.insns
        fun inPart(r: Int) = r >= 0 && part[r]

        // Locals the part needs, and locals it changes that are needed after it
        val touched = BitSet()
        val written = BitSet()
        part.stream().forEach { r ->
            when (val insn = insns[code.real[r]]) {
                is VarInsnNode -> {
                    touched.set(insn.`var`)
                    if (insn.opcode >= Opcodes.ISTORE) written.set(insn.`var`)
                }
                is IincInsnNode -> {
                    touched.set(insn.`var`)
                    written.set(insn.`var`)
                }
            }
        }
        val exits = LinkedHashSet<Int>()
        val exitHandlers = LinkedHashSet<Int>()
        part.stream().forEach { r ->
            code.successors[r].filterTo(exits) { !inPart(it) }
            code.handlers[r].filterTo(exitHandlers) { !inPart(it) }
        }
        val liveAfter = BitSet().apply { (exits + exitHandlers).forEach { or(code.liveIn[it]) } }
        val outputs = (written.clone() as BitSet).apply { and(liveAfter) }
        val inputs = (code.liveIn[root].clone() as BitSet).apply { and(touched) }

        // Each kind of value gets an array, each passed local a place in it
        val returnType = Type.getReturnType(method.desc)
        val returnKind = if (returnType.sort == Type.VOID) null else kindOf(returnType)
        val places = HashMap<Int, Int>()
        val sizes = IntArray(KINDS.size)
        val passed = (inputs.clone() as BitSet).apply { or(outputs) }
        passed.stream().forEach { slot -> places[slot] = sizes[KINDS.indexOf(code.kinds.getValue(slot))]++ }
        val returns = part.stream().anyMatch { insns[code.real[it]].opcode in Opcodes.IRETURN..Opcodes.RETURN }
        val returnPlace = returnKind?.takeIf { returns }?.let { sizes[KINDS.indexOf(it)]++ }
        val exceptionPlace = if (exitHandlers.isNotEmpty()) sizes[KINDS.indexOf(Type.OBJECT)]++ else null

        val ways = exits.toList() + listOfNotNull(RETURN_WAY.takeIf { returns }) + exitHandlers.map { -2 - it }
        val wayIds = ways.withIndex().associate { (id, way) -> way to id }
        val name = uniqueName("${method.name}\$part")
        val descriptor = Type.getMethodDescriptor(Type.INT_TYPE, *KINDS.map(::arrayType).toTypedArray())

        // The part's method, its arrays come first so every local moves up by their number
        val shift = KINDS.size
        val labels = HashMap<LabelNode, LabelNode>()
        insns.forEach { if (it is LabelNode) labels[it] = LabelNode() }
        val exitLabels = HashMap<Int, LabelNode>()
        fun exitLabel(r: Int) = exitLabels.getOrPut(r) { LabelNode() }
        val handlerLabels = exitHandlers.associateWith { LabelNode() }
        val returnLabel = LabelNode()
        val packLabel = LabelNode()
        val entryLabel = LabelNode()
        val temp = code.firstFreeSlot + shift
        val labelIndex = HashMap<LabelNode, Int>().apply { insns.forEachIndexed { i, insn -> if (insn is LabelNode) put(insn, i) } }
        fun targetOf(label: LabelNode): LabelNode {
            val r = code.nextReal[labelIndex.getValue(label)]
            return if (inPart(r)) labels.getValue(label) else exitLabel(r)
        }

        val body = InsnList()
        passed.stream().forEach { slot ->
            val kind = code.kinds.getValue(slot)
            if (inputs[slot]) {
                body.add(VarInsnNode(Opcodes.ALOAD, KINDS.indexOf(kind)))
                body.add(push(places.getValue(slot)))
                body.add(InsnNode(arrayLoad(kind)))
            } else {
                // Only read on the way out when the part did not write it, the value is not needed after then
                body.add(InsnNode(defaultValue(kind)))
            }
            body.add(VarInsnNode(store(kind), slot + shift))
        }
        body.add(JumpInsnNode(Opcodes.GOTO, entryLabel))
        for (i in insns.indices) {
            val insn = insns[i]
            when {
                insn is LabelNode -> body.add(labels.getValue(insn))
                insn is LineNumberNode -> if (inPart(code.nextReal[i])) body.add(insn.clone(labels))
                insn is FrameNode -> {}
                // A real instruction is its own next real instruction
                insn.opcode < 0 || !inPart(code.nextReal[i]) -> {}
                else -> {
                    val r = code.nextReal[i]
                    if (r == root) body.add(entryLabel)
                    val copy = when (insn) {
                        is JumpInsnNode -> JumpInsnNode(insn.opcode, targetOf(insn.label))
                        is TableSwitchInsnNode -> TableSwitchInsnNode(insn.min, insn.max, targetOf(insn.dflt), *insn.labels.map(::targetOf).toTypedArray())
                        is LookupSwitchInsnNode -> LookupSwitchInsnNode(targetOf(insn.dflt), insn.keys.toIntArray(), insn.labels.map(::targetOf).toTypedArray())
                        is VarInsnNode -> VarInsnNode(insn.opcode, insn.`var` + shift)
                        is IincInsnNode -> IincInsnNode(insn.`var` + shift, insn.incr)
                        else -> if (insn.opcode in Opcodes.IRETURN..Opcodes.RETURN) JumpInsnNode(Opcodes.GOTO, returnLabel) else insn.clone(labels)
                    }
                    body.add(copy)
                    val fallsThrough = !(insn is JumpInsnNode && insn.opcode == Opcodes.GOTO) && insn !is TableSwitchInsnNode &&
                        insn !is LookupSwitchInsnNode && insn.opcode !in Opcodes.IRETURN..Opcodes.RETURN && insn.opcode != Opcodes.ATHROW
                    if (fallsThrough) {
                        val next = code.nextReal[i + 1]
                        if (!inPart(next)) body.add(JumpInsnNode(Opcodes.GOTO, exitLabel(next)))
                    }
                }
            }
        }
        for ((r, label) in exitLabels) {
            body.add(label)
            body.add(push(wayIds.getValue(r)))
            body.add(JumpInsnNode(Opcodes.GOTO, packLabel))
        }
        if (returns) {
            body.add(returnLabel)
            if (returnKind != null) {
                body.add(VarInsnNode(store(returnKind), temp))
                body.add(VarInsnNode(Opcodes.ALOAD, KINDS.indexOf(returnKind)))
                body.add(push(returnPlace!!))
                body.add(VarInsnNode(load(returnKind), temp))
                body.add(InsnNode(arrayStore(returnKind)))
            }
            body.add(push(wayIds.getValue(RETURN_WAY)))
            body.add(JumpInsnNode(Opcodes.GOTO, packLabel))
        }
        for ((handler, label) in handlerLabels) {
            body.add(label)
            body.add(VarInsnNode(Opcodes.ASTORE, temp))
            body.add(VarInsnNode(Opcodes.ALOAD, KINDS.indexOf(Type.OBJECT)))
            body.add(push(exceptionPlace!!))
            body.add(VarInsnNode(Opcodes.ALOAD, temp))
            body.add(InsnNode(Opcodes.AASTORE))
            body.add(push(wayIds.getValue(-2 - handler)))
            body.add(JumpInsnNode(Opcodes.GOTO, packLabel))
        }
        body.add(packLabel)
        outputs.stream().forEach { slot ->
            val kind = code.kinds.getValue(slot)
            body.add(VarInsnNode(Opcodes.ALOAD, KINDS.indexOf(kind)))
            body.add(push(places.getValue(slot)))
            body.add(VarInsnNode(load(kind), slot + shift))
            body.add(InsnNode(arrayStore(kind)))
        }
        body.add(InsnNode(Opcodes.IRETURN))

        val partMethod = MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, name, descriptor, null, null)
        partMethod.instructions = body
        fun covers(block: TryCatchBlockNode): Boolean {
            for (i in labelIndex.getValue(block.start) until labelIndex.getValue(block.end)) {
                if (insns[i].opcode >= 0 && inPart(code.nextReal[i])) return true
            }
            return false
        }
        for (block in method.tryCatchBlocks) {
            if (!covers(block)) continue
            val handler = code.nextReal[labelIndex.getValue(block.handler)]
            val handlerLabel = if (inPart(handler)) labels.getValue(block.handler) else handlerLabels.getValue(handler)
            partMethod.tryCatchBlocks.add(TryCatchBlockNode(labels.getValue(block.start), labels.getValue(block.end), handlerLabel, block.type))
        }
        partMethod.maxLocals = temp + 2
        partMethod.maxStack = maxOf(method.maxStack, 4)
        owner.methods.add(partMethod)

        // The original method calls the part where the part began
        val arraySlot = code.firstFreeSlot
        val call = InsnList()
        val callStart = LabelNode()
        val callEnd = LabelNode()
        call.add(callStart)
        KINDS.forEachIndexed { k, kind ->
            if (sizes[k] == 0) return@forEachIndexed
            call.add(push(sizes[k]))
            call.add(if (kind == Type.OBJECT) TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object") else IntInsnNode(Opcodes.NEWARRAY, newArrayType(kind)))
            call.add(VarInsnNode(Opcodes.ASTORE, arraySlot + k))
        }
        inputs.stream().forEach { slot ->
            val kind = code.kinds.getValue(slot)
            call.add(VarInsnNode(Opcodes.ALOAD, arraySlot + KINDS.indexOf(kind)))
            call.add(push(places.getValue(slot)))
            call.add(VarInsnNode(load(kind), slot))
            call.add(InsnNode(arrayStore(kind)))
        }
        KINDS.indices.forEach { k -> call.add(if (sizes[k] == 0) InsnNode(Opcodes.ACONST_NULL) else VarInsnNode(Opcodes.ALOAD, arraySlot + k)) }
        call.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner.name, name, descriptor, owner.access and Opcodes.ACC_INTERFACE != 0))
        outputs.stream().forEach { slot ->
            val kind = code.kinds.getValue(slot)
            call.add(VarInsnNode(Opcodes.ALOAD, arraySlot + KINDS.indexOf(kind)))
            call.add(push(places.getValue(slot)))
            call.add(InsnNode(arrayLoad(kind)))
            call.add(VarInsnNode(store(kind), slot))
        }
        val wayLabels = ways.map { way ->
            when {
                way >= 0 -> labelBefore(insns[code.real[way]])
                else -> LabelNode()
            }
        }
        if (ways.isEmpty()) {
            // The part always throws, this is never reached
            call.add(InsnNode(Opcodes.POP))
            call.add(InsnNode(Opcodes.ACONST_NULL))
            call.add(InsnNode(Opcodes.ATHROW))
        } else {
            call.add(TableSwitchInsnNode(0, ways.size - 1, wayLabels[0], *wayLabels.toTypedArray()))
        }
        ways.forEachIndexed { id, way ->
            if (way == RETURN_WAY) {
                call.add(wayLabels[id])
                if (returnKind != null) {
                    call.add(VarInsnNode(Opcodes.ALOAD, arraySlot + KINDS.indexOf(returnKind)))
                    call.add(push(returnPlace!!))
                    call.add(InsnNode(arrayLoad(returnKind)))
                }
                call.add(InsnNode(returnType.getOpcode(Opcodes.IRETURN)))
            } else if (way < RETURN_WAY) {
                call.add(wayLabels[id])
                call.add(VarInsnNode(Opcodes.ALOAD, arraySlot + KINDS.indexOf(Type.OBJECT)))
                call.add(push(exceptionPlace!!))
                call.add(InsnNode(Opcodes.AALOAD))
                call.add(JumpInsnNode(Opcodes.GOTO, labelBefore(insns[code.real[-2 - way]])))
            }
        }
        call.add(callEnd)

        // Handlers of the original method no longer see the part's instructions, the part catches for them
        val rootNode = insns[code.real[root]]
        val rootIndex = code.real[root]
        val blocks = ArrayList<TryCatchBlockNode>()
        for (block in method.tryCatchBlocks) {
            val start = labelIndex.getValue(block.start)
            val end = labelIndex.getValue(block.end)
            val keepsInstructions = (start until end).any { insns[it].opcode >= 0 && !inPart(code.nextReal[it]) }
            if (!keepsInstructions) continue
            if (rootIndex in start until end) {
                blocks.add(TryCatchBlockNode(block.start, callStart, block.handler, block.type))
                blocks.add(TryCatchBlockNode(callEnd, block.end, block.handler, block.type))
            } else {
                blocks.add(block)
            }
        }
        method.tryCatchBlocks = blocks
        method.instructions.insertBefore(rootNode, call)
        part.stream().forEach { r -> method.instructions.remove(insns[code.real[r]]) }
        method.maxLocals = arraySlot + KINDS.size
        method.localVariables = null
    }

    /** A label right before [insn], an existing one when there is one */
    private fun labelBefore(insn: AbstractInsnNode): LabelNode {
        var previous = insn.previous
        while (previous != null && previous.opcode < 0) {
            if (previous is LabelNode) return previous
            previous = previous.previous
        }
        return LabelNode().also { method.instructions.insertBefore(insn, it) }
    }

    private fun uniqueName(base: String): String {
        while (true) {
            val name = "$base${++parts}"
            if (owner.methods.none { it.name == name }) return name
        }
    }

    companion object {
        /** Parts smaller than this are not worth the locals passed around */
        private const val MIN_PART_SIZE = 1500L
        private const val MAX_PART_SIZE = 50_000L
        private const val MAX_CANDIDATES = 200
        private const val ANALYZER_STACK = 1000

        /** The way out of a part that returns from the method, exception handlers are -2 - their instruction */
        private const val RETURN_WAY = -1

        private val KINDS = listOf(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE, Type.OBJECT)

        private fun kindOf(opcode: Int) = when (opcode) {
            Opcodes.ILOAD, Opcodes.ISTORE -> Type.INT
            Opcodes.LLOAD, Opcodes.LSTORE -> Type.LONG
            Opcodes.FLOAD, Opcodes.FSTORE -> Type.FLOAT
            Opcodes.DLOAD, Opcodes.DSTORE -> Type.DOUBLE
            else -> Type.OBJECT
        }

        private fun kindOf(type: Type) = when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Type.INT
            Type.LONG -> Type.LONG
            Type.FLOAT -> Type.FLOAT
            Type.DOUBLE -> Type.DOUBLE
            else -> Type.OBJECT
        }

        private fun arrayType(kind: Int): Type = when (kind) {
            Type.INT -> Type.getType("[I")
            Type.LONG -> Type.getType("[J")
            Type.FLOAT -> Type.getType("[F")
            Type.DOUBLE -> Type.getType("[D")
            else -> Type.getType("[Ljava/lang/Object;")
        }

        private fun newArrayType(kind: Int) = when (kind) {
            Type.INT -> Opcodes.T_INT
            Type.LONG -> Opcodes.T_LONG
            Type.FLOAT -> Opcodes.T_FLOAT
            else -> Opcodes.T_DOUBLE
        }

        private fun typeOf(kind: Int): Type = when (kind) {
            Type.INT -> Type.INT_TYPE
            Type.LONG -> Type.LONG_TYPE
            Type.FLOAT -> Type.FLOAT_TYPE
            Type.DOUBLE -> Type.DOUBLE_TYPE
            else -> Type.getType("Ljava/lang/Object;")
        }

        private fun load(kind: Int) = typeOf(kind).getOpcode(Opcodes.ILOAD)
        private fun store(kind: Int) = typeOf(kind).getOpcode(Opcodes.ISTORE)
        private fun arrayLoad(kind: Int) = typeOf(kind).getOpcode(Opcodes.IALOAD)
        private fun arrayStore(kind: Int) = typeOf(kind).getOpcode(Opcodes.IASTORE)

        private fun defaultValue(kind: Int) = when (kind) {
            Type.INT -> Opcodes.ICONST_0
            Type.LONG -> Opcodes.LCONST_0
            Type.FLOAT -> Opcodes.FCONST_0
            Type.DOUBLE -> Opcodes.DCONST_0
            else -> Opcodes.ACONST_NULL
        }

        private fun push(value: Int): AbstractInsnNode = when (value) {
            in -1..5 -> InsnNode(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
            else -> IntInsnNode(Opcodes.SIPUSH, value)
        }

        /** Upper bound of an instruction's bytes, jumps as if widened */
        private fun estimatedSize(insn: AbstractInsnNode): Int = when (insn) {
            is VarInsnNode -> if (insn.`var` < 4) 1 else if (insn.`var` < 256) 2 else 4
            is IincInsnNode -> 6
            is IntInsnNode -> if (insn.opcode == Opcodes.SIPUSH) 3 else 2
            is JumpInsnNode -> 8
            is TableSwitchInsnNode -> 16 + 4 * insn.labels.size
            is LookupSwitchInsnNode -> 12 + 8 * insn.labels.size
            is MethodInsnNode -> if (insn.opcode == Opcodes.INVOKEINTERFACE) 5 else 3
            is InsnNode -> 1
            else -> when (insn.type) {
                AbstractInsnNode.INVOKE_DYNAMIC_INSN -> 5
                AbstractInsnNode.MULTIANEWARRAY_INSN -> 4
                else -> 3
            }
        }
    }
}
