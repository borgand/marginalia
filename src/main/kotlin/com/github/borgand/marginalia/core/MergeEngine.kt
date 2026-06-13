package com.github.borgand.marginalia.core

import com.github.difflib.DiffUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Merge engine (PRD F5): applies agent edits expressed against a *base version*
 * (content snapshot recorded at read_doc) into the live buffer, re-anchored through
 * whatever the user typed since. Conflict policy: **user wins** — an edit whose
 * old_text no longer matches the live buffer is returned as a conflict, never forced.
 *
 * Re-anchoring tiers: exact match (nearest occurrence to the diff-mapped base
 * position) → whitespace-tolerant match → conflict (PRD D7: no edit-distance fuzzy yet).
 */
@Service(Service.Level.PROJECT)
class MergeEngine(private val project: Project) {

    data class Edit(val oldText: String, val newText: String)
    data class Conflict(val index: Int, val reason: String, val currentText: String)

    sealed interface Result {
        object StaleBase : Result
        data class Outcome(val applied: List<Int>, val conflicts: List<Conflict>) : Result
    }

    private data class Snapshot(val version: Long, val content: String)

    private val bases = ConcurrentHashMap<String, ArrayDeque<Snapshot>>()

    companion object {
        private const val MAX_SNAPSHOTS_PER_DOC = 8
    }

    /** Record [content] as the merge base for [path] at [version] (side effect of read_doc). */
    fun recordBase(path: String, version: Long, content: String) {
        val deque = bases.computeIfAbsent(path) { ArrayDeque() }
        synchronized(deque) {
            deque.removeAll { it.version == version }
            deque.addLast(Snapshot(version, content))
            while (deque.size > MAX_SNAPSHOTS_PER_DOC) deque.removeFirst()
        }
    }

    private fun findBase(path: String, version: Long): Snapshot? {
        val deque = bases[path] ?: return null
        synchronized(deque) {
            return deque.find { it.version == version }
        }
    }

    /**
     * Apply [edits] (old_text from the [baseVersion] content) onto the live [document].
     * Safe to call from any thread; hops to the EDT and wraps the mutation in a single
     * write command. Partial success is normal.
     */
    fun applyEdits(document: Document, path: String, baseVersion: Long, edits: List<Edit>): Result {
        val base = findBase(path, baseVersion) ?: return Result.StaleBase
        return applyAgainstBase(document, base.content, edits)
    }

    /** Core algorithm, reusable with an explicit base (F6 disk-merge uses this too). */
    fun applyAgainstBase(document: Document, baseContent: String, edits: List<Edit>): Result.Outcome {
        lateinit var outcome: Result.Outcome
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                outcome = doApply(document, baseContent, edits)
            }
        }
        return outcome
    }

    private fun doApply(document: Document, baseContent: String, edits: List<Edit>): Result.Outcome {
        val current = document.text
        val applied = mutableListOf<Int>()
        val conflicts = mutableListOf<Conflict>()

        data class Replacement(val index: Int, val start: Int, val end: Int, val newText: String)
        val replacements = mutableListOf<Replacement>()

        for ((index, edit) in edits.withIndex()) {
            if (edit.oldText.isEmpty()) {
                conflicts += Conflict(index, "old_text must not be empty", "")
                continue
            }
            val basePos = baseContent.indexOf(edit.oldText)
            if (basePos < 0) {
                conflicts += Conflict(index, "old_text not found in base_version content", "")
                continue
            }
            val expected = mapBaseOffset(baseContent, current, basePos)
            val range = locateExact(current, edit.oldText, expected)
                ?: locateWhitespaceTolerant(current, edit.oldText, expected)
            if (range == null) {
                conflicts += Conflict(
                    index,
                    "old_text no longer present in the live buffer (user edit wins)",
                    currentRegion(baseContent, current, basePos, edit.oldText.length),
                )
                continue
            }
            replacements += Replacement(index, range.first, range.last + 1, edit.newText)
        }

        // user typing can't overlap itself, but agent edits can — first writer wins
        val accepted = mutableListOf<Replacement>()
        for (r in replacements) {
            val overlaps = accepted.any { it.start < r.end && r.start < it.end }
            if (overlaps) {
                conflicts += Conflict(r.index, "overlaps an earlier edit in this call", current.substring(r.start, r.end))
            } else {
                accepted += r
            }
        }

        for (r in accepted.sortedByDescending { it.start }) {
            document.replaceString(r.start, r.end, r.newText)
        }
        applied += accepted.map { it.index }.sorted()

        return Result.Outcome(applied, conflicts.sortedBy { it.index })
    }

    /** Nearest exact occurrence of [needle] to [expected], as a closed char range. */
    private fun locateExact(haystack: String, needle: String, expected: Int): IntRange? {
        var best: Int? = null
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            if (best == null || abs(i - expected) < abs(best - expected)) best = i
            i = haystack.indexOf(needle, i + 1)
        }
        return best?.let { it..it + needle.length - 1 }
    }

    /** Match [needle] with any inter-token whitespace (user reformatted since base). */
    private fun locateWhitespaceTolerant(haystack: String, needle: String, expected: Int): IntRange? {
        val tokens = needle.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val pattern = Regex(tokens.joinToString("\\s+") { Regex.escape(it) })
        var best: IntRange? = null
        for (m in pattern.findAll(haystack)) {
            if (best == null || abs(m.range.first - expected) < abs(best.first - expected)) best = m.range
        }
        return best
    }

    /**
     * Map a base offset to the corresponding live-buffer offset by accumulating
     * line deltas of all changes that lie fully before the base line.
     */
    private fun mapBaseOffset(base: String, current: String, baseOffset: Int): Int {
        val baseLines = base.lines()
        val currentLines = current.lines()
        val baseLine = base.substring(0, baseOffset).count { it == '\n' }
        var lineDelta = 0
        for (delta in DiffUtils.diff(baseLines, currentLines).deltas) {
            if (delta.source.position + delta.source.size() <= baseLine) {
                lineDelta += delta.target.size() - delta.source.size()
            }
        }
        val targetLine = (baseLine + lineDelta).coerceIn(0, (currentLines.size - 1).coerceAtLeast(0))
        return lineStartOffset(current, targetLine)
    }

    /** The live-buffer text where the base's old_text used to live — context for conflicts. */
    private fun currentRegion(base: String, current: String, basePos: Int, oldLen: Int): String {
        val currentLines = current.lines()
        val startLine = base.substring(0, basePos).count { it == '\n' }
        val endLine = base.substring(0, (basePos + oldLen).coerceAtMost(base.length)).count { it == '\n' }
        var startDelta = 0
        var endDelta = 0
        for (delta in DiffUtils.diff(base.lines(), currentLines).deltas) {
            val change = delta.target.size() - delta.source.size()
            if (delta.source.position + delta.source.size() <= startLine) startDelta += change
            if (delta.source.position <= endLine) endDelta += change
        }
        val from = (startLine + startDelta).coerceIn(0, (currentLines.size - 1).coerceAtLeast(0))
        val to = (endLine + endDelta).coerceIn(from, (currentLines.size - 1).coerceAtLeast(0))
        return currentLines.subList(from, to + 1).joinToString("\n")
    }

    private fun lineStartOffset(text: String, line: Int): Int {
        var offset = 0
        var remaining = line
        while (remaining > 0) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return offset
            offset = next + 1
            remaining--
        }
        return offset
    }
}
