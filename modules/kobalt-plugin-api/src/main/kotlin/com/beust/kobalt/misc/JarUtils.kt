package com.beust.kobalt.misc

import com.beust.kobalt.*
import com.google.common.io.CharStreams
import java.io.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class JarUtils {
    companion object {
        val DEFAULT_HANDLER: (Exception) -> Unit = { ex: Exception ->
            // Ignore duplicate entry exceptions
            if (! ex.message?.contains("duplicate")!!) {
                throw ex
            }
        }

        fun addFiles(directory: String, files: List<IncludedFile>, target: ZipOutputStream,
                expandJarFiles: Boolean,
                onError: (Exception) -> Unit = DEFAULT_HANDLER) {
            files.forEach {
                addSingleFile(directory, it, target, expandJarFiles, onError)
            }
        }

        private val DEFAULT_JAR_EXCLUDES =
                Glob("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

        fun addSingleFile(directory: String, file: IncludedFile, outputStream: ZipOutputStream,
                expandJarFiles: Boolean, onError: (Exception) -> Unit = DEFAULT_HANDLER) {
            val foundFiles = file.allFromFiles(directory)
            foundFiles.forEach { foundFile ->

                // Turn the found file into the local physical file that will be put in the jar file
                val fromFile = file.from(foundFile.path)
                val localFile = if (fromFile.isAbsolute) fromFile
                    else File(directory, fromFile.path)

                if (!localFile.exists()) {
                    throw AssertionError("File should exist: $localFile")
                }

                if (foundFile.isDirectory) {
                    kobaltLog(2, "  Writing contents of directory $foundFile")

                    // Directory
                    val includedFile = IncludedFile(From(""), To(""), listOf(IFileSpec.GlobSpec("**")))
                    addSingleFile(localFile.path, includedFile, outputStream, expandJarFiles)
                } else {
                    if (file.expandJarFiles && foundFile.name.endsWith(".jar") && ! file.from.contains("resources")) {
                        kobaltLog(2, "  Writing contents of jar file $foundFile")
                        val stream = JarInputStream(FileInputStream(localFile))
                        var entry = stream.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && !KFiles.isExcluded(entry.name, DEFAULT_JAR_EXCLUDES)) {
                                val ins = JarFile(localFile).getInputStream(entry)
                                addEntry(ins, JarEntry(entry), outputStream, onError)
                            }
                            entry = stream.nextEntry
                        }
                    } else {
                        val entryFileName = KFiles.fixSlashes(file.to(foundFile.path))
                        val entry = JarEntry(entryFileName)
                        entry.time = localFile.lastModified()
                        addEntry(FileInputStream(localFile), entry, outputStream, onError)
                    }
                }
            }
        }

        private fun addEntry(inputStream: InputStream, entry: ZipEntry, outputStream: ZipOutputStream,
                onError: (Exception) -> Unit = DEFAULT_HANDLER) {
            var bis: BufferedInputStream? = null
            try {
                outputStream.putNextEntry(entry)
                bis = BufferedInputStream(inputStream)

                val buffer = ByteArray(50 * 1024)
                while (true) {
                    val count = bis.read(buffer)
                    if (count == -1) break
                    outputStream.write(buffer, 0, count)
                }
                outputStream.closeEntry()
            } catch(ex: Exception) {
                onError(ex)
            } finally {
                bis?.close()
            }
        }

        fun extractTextFile(zip : ZipFile, fileName: String) : String? {
            val enumEntries = zip.entries()
            while (enumEntries.hasMoreElements()) {
                val file = enumEntries.nextElement()
                if (file.name == fileName) {
                    kobaltLog(2, "Found $fileName in ${zip.name}")
                    zip.getInputStream(file).use { ins ->
                        return CharStreams.toString(InputStreamReader(ins, "UTF-8"))
                    }
                }
            }
            return null
        }

        fun extractJarFile(file: File, destDir: File) = extractZipFile(JarFile(file), destDir)

        fun extractZipFile(zipFile: ZipFile, destDir: File) {
            val enumEntries = zipFile.entries()
            while (enumEntries.hasMoreElements()) {
                val file = enumEntries.nextElement()
                val f = File(destDir.path + File.separator + file.name)
                if (file.isDirectory) {
                    f.mkdir()
                    continue
                }

                zipFile.getInputStream(file).use { ins ->
                    f.parentFile.mkdirs()
                    FileOutputStream(f).use { fos ->
                        while (ins.available() > 0) {
                            fos.write(ins.read())
                        }
                    }
                }
            }
        }
    }
}

