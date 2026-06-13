package com.github.borgand.marginalia.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Which files are "co-edited" (PRD F4): per-doc monotonically increasing version
 * (bumped on every buffer change — the staleness anchor for the merge engine) and
 * the source of truth for ~/.marginalia/active-docs.json.
 */
@Service(Service.Level.PROJECT)
class DocRegistry(@Suppress("unused") private val project: Project) : Disposable {

    private class Entry(val file: VirtualFile) {
        val version = AtomicLong(1)

        /** Disk content at last load/save — the 3-way base for the F6 reload merge. */
        @Volatile
        var lastSynced: String? = null
    }

    private val docs = ConcurrentHashMap<String, Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { listeners.remove(listener) }
    }

    private fun notifyChanged() = listeners.forEach { it() }

    /** Returns false if already registered or the file has no document. */
    fun register(file: VirtualFile): Boolean {
        if (docs.containsKey(file.path)) return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val entry = Entry(file)
        entry.lastSynced = document.text
        docs[file.path] = entry
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // entry may have been unregistered; only bump while live
                if (docs[file.path] === entry) entry.version.incrementAndGet()
            }
        }, this)
        ActiveDocsFile.rewrite()
        notifyChanged()
        return true
    }

    fun unregister(path: String) {
        if (docs.remove(path) != null) {
            ActiveDocsFile.rewrite()
            notifyChanged()
        }
    }

    fun isCoEdited(path: String): Boolean = docs.containsKey(path)

    fun version(path: String): Long? = docs[path]?.version?.get()

    fun paths(): List<String> = docs.keys.sorted()

    fun fileFor(path: String): VirtualFile? = docs[path]?.file

    fun lastSynced(path: String): String? = docs[path]?.lastSynced

    fun setLastSynced(path: String, content: String) {
        docs[path]?.lastSynced = content
    }

    fun clear() {
        docs.clear()
        ActiveDocsFile.rewrite()
        notifyChanged()
    }

    override fun dispose() {
        docs.clear()
    }
}
