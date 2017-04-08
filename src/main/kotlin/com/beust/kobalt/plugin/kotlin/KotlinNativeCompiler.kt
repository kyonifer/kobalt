package com.beust.kobalt.plugin.kotlin

import com.beust.kobalt.OperatingSystem
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.*
import com.beust.kobalt.internal.ParallelLogger
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.Strings
import com.google.inject.Inject
import com.google.inject.Singleton
import java.io.File

@Singleton
class KotlinNativeCompiler @Inject constructor(val kobaltLog: ParallelLogger) : ICompiler {
    
    override fun compile(project: Project, context: KobaltContext, info: CompilerActionInfo) : TaskResult {
        fun logk(level: Int, message: CharSequence) = kobaltLog.log(project.name, level, message)

        logk(1, "  Kotlin/Native compiling " + Strings.pluralizeAll(info.sourceFiles.size, "file"))

        val candidateCompilers = listOf("kotlinc-native", "konanc")
        val candidateBindings= listOf("cinterop")
        val runtime = OperatingSystem.current().currentKotlinNativeRuntime() 
                      ?: return TaskResult(false, "Kobalt doesn't support Kotlin/Native on this OS yet.")
        val executable = runtime.findExecutable(candidateCompilers) 
                         ?: return TaskResult(false, "Cannot find kotlin-native compiler.")
        val interop = runtime.findExecutable(candidateBindings)
                         ?: return TaskResult(false, "Cannot find kotlin-native cinterop binding.")

        val srcFiles = KFiles.findSourceFiles(project.directory, project.sourceDirectories, KotlinPlugin.SOURCE_SUFFIXES)

        val interopFiles = srcFiles.filter {File(it).isFile &&  it.endsWith("def") }
        val ktFiles = srcFiles.filter { File(it).isFile && it.endsWith("kt") }
        
        val outPath = KFiles.makeDir(info.directory!!, info.outputDir.path).path
        val defPath = KFiles.makeDir(outPath, "defs").path
        
        if (interopFiles.isNotEmpty()) {

            // konanc currently dies with "List has more than one element" 
            // if we try to process more than one def at a time
            // Eventually we want to use "interopFiles.flatMap { listOf("-def", it) }"
            // instead
            interopFiles.forEach { interopFile ->
                val interopFileName = File(interopFile).nameWithoutExtension
                val interopArgs = arrayListOf(interop.absolutePath)
                interopArgs.addAll(info.compilerArgs)
                // Seems to only work with ./foo form, no directories in the path. we'll
                // run the pb in a chdir later
                interopArgs.addAll(listOf("-def", "./${File(interopFile).name}"))
                interopArgs.addAll(listOf("-o", File(defPath,"$interopFileName.def.bc").absolutePath))
                
                logk(2, "  Kotlin/Native processing $interopFile files")
                logk(2, "  Kotlin/Native process line: $interopArgs")
                
                val pb = ProcessBuilder(interopArgs)
                pb.inheritIO()
                // Bug in cinterop requires running in same dir as def file
                pb.directory(File(interopFile).parentFile)
                val process = pb.start()
                val errorCode = process.waitFor()
                val errorMessage = "Something went wrong running $interop"
                if (errorCode==1) {
                    val message = "Compilation errors, command:\n$interopArgs\n" + errorMessage
                    logk(1, message)
                    return TaskResult(false, message)
                }
            }
        }
        val allArgs = arrayListOf(executable.absolutePath)
        allArgs.addAll(info.compilerArgs)
        allArgs.addAll(ktFiles)
        
        // This might be cleaner with the alternative pipeline:
        //   "konanc -nolink ktFiles" -> llc -> llc ${INSTALL}/lib/host/*.bc -> system linker pipeline,
        allArgs.addAll(listOf("-o",File(outPath,"a.out").path, "-opt"))

        logk(2, "  Kotlin/Native compiling $ktFiles files")
        logk(2, "  Kotlin/Native compile line: $allArgs")
        
        val pb = ProcessBuilder(allArgs)
        pb.inheritIO()
        val process = pb.start()
        val errorCode = process.waitFor()
        val errorMessage = "Something went wrong running $executable"
        return if (errorCode==0) {
            TaskResult(true, "Compilation succeeded")
        } else {
            val message = "Compilation errors, command:\n$allArgs\n" + errorMessage
            logk(1, message)
            TaskResult(false, message)
        }
    }
}


fun OperatingSystem.currentKotlinNativeRuntime(): KotlinNativeRuntime? {
    return when (this) {
        is OperatingSystem.Linux -> { LinuxKotlinNativeRuntime() }
        else                     -> { null }
    }
}

interface KotlinNativeRuntime {
    fun findExecutable(candidateCommands: List<String>) : File?
}

// TODO: support setting custom path via e.g. env variable
class LinuxKotlinNativeRuntime: KotlinNativeRuntime {
    val candidateDirs = listOf("/usr/bin/", "/usr/local/bin/")
    override fun findExecutable(candidateCommands: List<String>) : File? {
        candidateDirs.forEach { dir ->
            candidateCommands.forEach { cmd ->
                File(dir+cmd).apply {
                    if(exists())
                        return this
                }
            }
        }
        return null
    }
}

