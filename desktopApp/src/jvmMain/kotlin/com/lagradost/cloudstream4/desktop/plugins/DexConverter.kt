package com.lagradost.cloudstream4.desktop.plugins

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.Dex2Asm
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.dex.LambadaNameSafeClassAdapter
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.DexFileReader
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.AppLog
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns an Android extension (.cs3, a zip with classes.dex and manifest.json) into a JVM jar.
 */
object DexConverter {
    /** Bump when the conversion changes, older conversions are then repaired on startup */
    const val VERSION = 3

    /** Conversions older than this cannot be repaired in place and are converted again from the original */
    const val RECONVERT_BELOW = 2

    /**
     * Applies the bytecode fixes of the current [VERSION] to a jar converted by an older version. Returns false,
     * leaving the jar as it is, when it has to be converted again from the original instead.
     */
    fun repairJar(jar: File): Boolean {
        val needsConversion = ZipFile(jar).use { zip ->
            zip.entries().asSequence().any { entry ->
                entry.name.endsWith(".class") && OversizedMethods.hasTooLargeMethod(zip.getInputStream(entry).use { it.readBytes() })
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
        val readerConfig = Dex2jar::class.java.getDeclaredField("readerConfig").apply { isAccessible = true }.getInt(dex2jar)
        val file = DexFileNode()
        try {
            dex2jar.reader.accept(file, readerConfig or DexFileReader.IGNORE_READ_EXCEPTION)
        } catch (e: Exception) {
            dex2jar.exceptionHandler.handleFileException(e)
        }
        // dex2jar's own converter, it applies the options above
        val converter = Class.forName("com.googlecode.d2j.dex.Dex2jar\$2")
            .getDeclaredConstructor(Dex2jar::class.java, DexExceptionHandler::class.java)
            .apply { isAccessible = true }
            .newInstance(dex2jar, dex2jar.exceptionHandler) as Dex2Asm
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
