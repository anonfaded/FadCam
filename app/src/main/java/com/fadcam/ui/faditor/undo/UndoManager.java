package com.fadcam.ui.faditor.undo;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Manages undo/redo history for the Faditor editor.
 *
 * <p>Maintains two stacks: undo and redo. When a new action is performed,
 * it is pushed onto the undo stack and the redo stack is cleared (any
 * previously undone branch is discarded).</p>
 *
 * <p>Supports both action-based undo/redo (in-session, precise) and
 * snapshot-based undo/redo (persistent across sessions). When a project is
 * saved, snapshots of the project state are stored alongside each action
 * description. On reload, snapshot-only entries enable undo/redo without
 * the original {@link EditAction} objects.</p>
 *
 * <p>The history has a configurable maximum size to bound memory usage.</p>
 */
public class UndoManager {

    private static final String TAG = "UndoManager";
    private static final int DEFAULT_MAX_HISTORY = 200;

    @NonNull
    private final Deque<HistoryEntry> undoStack;

    @NonNull
    private final Deque<HistoryEntry> redoStack;

    private final int maxHistory;

    /** Listener for undo/redo state changes (stack empty/non-empty). */
    @Nullable
    private OnStateChangedListener listener;

    /** Provider for capturing & restoring project snapshots (set by editor). */
    @Nullable
    private SnapshotRestorer snapshotRestorer;

    // ── Inner types ──────────────────────────────────────────────────

    /**
     * A single entry in the undo/redo history.
     *
     * <p>In-session entries have both {@code action} and {@code snapshotBefore}.
     * Entries loaded from disk have only {@code description} and {@code snapshotBefore}
     * (action is null). {@code snapshotAfter} is populated lazily during undo
     * to enable redo for snapshot-only entries.</p>
     */
    public static class HistoryEntry {
        /** The reversible action (null for entries loaded from disk). */
        @Nullable
        EditAction action;

        /** Human-readable description (e.g. "Rotated 90°"). */
        @NonNull
        final String description;

        /** Project JSON snapshot captured BEFORE this action was applied. */
        @Nullable
        String snapshotBefore;

        /**
         * Project JSON snapshot captured AFTER this action was applied.
         * Set lazily during undo (captures current state before reverting).
         */
        @Nullable
        String snapshotAfter;

        HistoryEntry(@Nullable EditAction action,
                     @NonNull String description,
                     @Nullable String snapshotBefore) {
            this.action = action;
            this.description = description;
            this.snapshotBefore = snapshotBefore;
        }

        public @NonNull String getDescription() { return description; }
        public @Nullable String getSnapshotBefore() { return snapshotBefore; }
    }

    /**
     * Callback to notify when undo/redo availability changes.
     */
    public interface OnStateChangedListener {
        /**
         * Called whenever the undo or redo stack changes.
         *
         * @param canUndo whether there are actions to undo
         * @param canRedo whether there are actions to redo
         */
        void onUndoRedoStateChanged(boolean canUndo, boolean canRedo);
    }

    /**
     * Interface for capturing and restoring project state snapshots.
     * Implemented by the editor activity.
     */
    public interface SnapshotRestorer {
        /**
         * Capture the current project state as a JSON string.
         *
         * @return project JSON, or null if capture is unavailable
         */
        @Nullable
        String captureSnapshot();

        /**
         * Restore the project from a JSON snapshot.
         * Must replace the project object and refresh all UI.
         *
         * @param projectJson the JSON string to restore from
         */
        void restoreFromSnapshot(@NonNull String projectJson);
    }

    // ── Construction ─────────────────────────────────────────────────

    public UndoManager() {
        this(DEFAULT_MAX_HISTORY);
    }

    public UndoManager(int maxHistory) {
        this.maxHistory = maxHistory;
        this.undoStack = new ArrayDeque<>();
        this.redoStack = new ArrayDeque<>();
    }

    // ── Configuration ────────────────────────────────────────────────

    /**
     * Set the listener for state changes.
     */
    public void setOnStateChangedListener(@Nullable OnStateChangedListener listener) {
        this.listener = listener;
    }

    /**
     * Set the snapshot restorer (provided by the editor).
     * Must be set before any undo/redo operations involving snapshots.
     */
    public void setSnapshotRestorer(@Nullable SnapshotRestorer restorer) {
        this.snapshotRestorer = restorer;
    }

    // ── Recording ────────────────────────────────────────────────────

    /**
     * Record a new action. The action should already have been applied
     * to the model; this method just records it for undo.
     *
     * <p>Automatically captures a project snapshot (if a restorer is set)
     * to enable persistent undo across sessions.</p>
     *
     * <p>Clears the redo stack (new action forks a new branch).</p>
     *
     * @param action the action that was just performed
     */
    public void recordAction(@NonNull EditAction action) {
        String snapshot = null;
        if (snapshotRestorer != null) {
            snapshot = snapshotRestorer.captureSnapshot();
        }

        HistoryEntry entry = new HistoryEntry(action, action.getDescription(), snapshot);
        undoStack.push(entry);
        redoStack.clear();

        // Trim history if exceeding max
        while (undoStack.size() > maxHistory) {
            ((ArrayDeque<HistoryEntry>) undoStack).removeLast();
        }

        Log.d(TAG, "Recorded: " + action.getDescription()
                + " (undo=" + undoStack.size() + ", redo=0"
                + ", snapshot=" + (snapshot != null) + ")");
        notifyListener();
    }

    // ── Undo / Redo ──────────────────────────────────────────────────

    /**
     * Undo the most recent action.
     *
     * <p>For in-session entries (action != null), calls {@link EditAction#undo()}.
     * For snapshot-only entries (loaded from disk), restores via
     * {@link SnapshotRestorer#restoreFromSnapshot(String)}.</p>
     *
     * @return true if an action was undone, false if nothing to undo
     */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            Log.d(TAG, "Nothing to undo");
            return false;
        }

        HistoryEntry entry = undoStack.pop();

        // Capture current state as "after" (needed for redo)
        if (snapshotRestorer != null) {
            entry.snapshotAfter = snapshotRestorer.captureSnapshot();
        }

        if (entry.action != null) {
            // In-session: precise action-based undo
            entry.action.undo();
            Log.d(TAG, "Undone (action): " + entry.description);
        } else if (entry.snapshotBefore != null && snapshotRestorer != null) {
            // Loaded from disk: snapshot-based undo
            snapshotRestorer.restoreFromSnapshot(entry.snapshotBefore);
            // After snapshot restore, invalidate action refs on redo stack
            invalidateRedoActions();
            Log.d(TAG, "Undone (snapshot): " + entry.description);
        }

        redoStack.push(entry);

        Log.d(TAG, "(undo=" + undoStack.size() + ", redo=" + redoStack.size() + ")");
        notifyListener();
        return true;
    }

    /**
     * Redo the most recently undone action.
     *
     * <p>For in-session entries, calls {@link EditAction#execute()}.
     * For snapshot-only entries, restores the "after" snapshot.</p>
     *
     * @return true if an action was redone, false if nothing to redo
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            Log.d(TAG, "Nothing to redo");
            return false;
        }

        HistoryEntry entry = redoStack.pop();

        if (entry.action != null) {
            // In-session: precise action-based redo
            entry.action.execute();
            Log.d(TAG, "Redone (action): " + entry.description);
        } else if (entry.snapshotAfter != null && snapshotRestorer != null) {
            // Loaded from disk: snapshot-based redo
            snapshotRestorer.restoreFromSnapshot(entry.snapshotAfter);
            Log.d(TAG, "Redone (snapshot): " + entry.description);
        }

        undoStack.push(entry);

        Log.d(TAG, "(undo=" + undoStack.size() + ", redo=" + redoStack.size() + ")");
        notifyListener();
        return true;
    }

    // ── Queries ──────────────────────────────────────────────────────

    /** Whether there are actions to undo. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Whether there are actions to redo. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Number of actions that can be undone. */
    public int getUndoCount() {
        return undoStack.size();
    }

    /** Number of actions that can be redone. */
    public int getRedoCount() {
        return redoStack.size();
    }

    /** Get the description of the action that would be undone. */
    @Nullable
    public String peekUndoDescription() {
        return undoStack.isEmpty() ? null : undoStack.peek().description;
    }

    /** Get the description of the action that would be redone. */
    @Nullable
    public String peekRedoDescription() {
        return redoStack.isEmpty() ? null : redoStack.peek().description;
    }

    /** Clear all history. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        Log.d(TAG, "History cleared");
        notifyListener();
    }

    /** Total number of recorded entries (undo + redo). */
    public int size() {
        return undoStack.size() + redoStack.size();
    }

    // ── Persistence ──────────────────────────────────────────────────

    /**
     * Get the undo history as a serializable list (bottom-to-top order).
     * Used by {@code ProjectStorage} to persist undo history alongside
     * the project file.
     *
     * @return list of history entries (oldest first)
     */
    @NonNull
    public List<HistoryEntry> getUndoHistory() {
        // ArrayDeque iteration is top-to-bottom (LIFO); reverse for persistence
        List<HistoryEntry> list = new ArrayList<>(undoStack);
        // Reverse so oldest is first
        java.util.Collections.reverse(list);
        return list;
    }

    /**
     * Load undo history from persisted data (oldest first).
     * Creates snapshot-only entries (no {@link EditAction}) that enable
     * undo via snapshot restoration.
     *
     * @param descriptions ordered list of action descriptions (oldest first)
     * @param snapshots    ordered list of project JSON snapshots (oldest first)
     */
    public void loadHistory(@NonNull List<String> descriptions,
                            @NonNull List<String> snapshots) {
        clear();
        int count = Math.min(descriptions.size(), snapshots.size());
        // Push oldest first (addLast) so the most recent ends up on top
        for (int i = 0; i < count; i++) {
            HistoryEntry entry = new HistoryEntry(null, descriptions.get(i), snapshots.get(i));
            ((ArrayDeque<HistoryEntry>) undoStack).addLast(entry);
        }
        Log.d(TAG, "Loaded " + count + " history entries from disk");
        notifyListener();
    }

    // ── Internal ─────────────────────────────────────────────────────

    /**
     * After a snapshot-based project restore, action references on the
     * redo stack point to stale model objects. Null them out so redo
     * falls back to snapshot restoration.
     */
    private void invalidateRedoActions() {
        for (HistoryEntry entry : redoStack) {
            if (entry.action != null) {
                entry.action = null;
            }
        }
        Log.d(TAG, "Invalidated action refs on redo stack (" + redoStack.size() + " entries)");
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onUndoRedoStateChanged(canUndo(), canRedo());
        }
    }
}
