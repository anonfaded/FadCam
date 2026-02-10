package com.fadcam.ui.faditor.undo;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages undo/redo history for the Faditor editor.
 *
 * <p>Maintains two stacks: undo and redo. When a new action is performed,
 * it is pushed onto the undo stack and the redo stack is cleared (any
 * previously undone branch is discarded).</p>
 *
 * <p>The history has a configurable maximum size to bound memory usage.</p>
 */
public class UndoManager {

    private static final String TAG = "UndoManager";
    private static final int DEFAULT_MAX_HISTORY = 50;

    @NonNull
    private final Deque<EditAction> undoStack;

    @NonNull
    private final Deque<EditAction> redoStack;

    private final int maxHistory;

    /** Listener for undo/redo state changes (stack empty/non-empty). */
    @Nullable
    private OnStateChangedListener listener;

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

    public UndoManager() {
        this(DEFAULT_MAX_HISTORY);
    }

    public UndoManager(int maxHistory) {
        this.maxHistory = maxHistory;
        this.undoStack = new ArrayDeque<>();
        this.redoStack = new ArrayDeque<>();
    }

    /**
     * Set the listener for state changes.
     */
    public void setOnStateChangedListener(@Nullable OnStateChangedListener listener) {
        this.listener = listener;
    }

    /**
     * Record a new action. The action should already have been applied
     * to the model; this method just records it for undo.
     *
     * <p>Clears the redo stack (new action forks a new branch).</p>
     *
     * @param action the action that was just performed
     */
    public void recordAction(@NonNull EditAction action) {
        undoStack.push(action);
        redoStack.clear();

        // Trim history if exceeding max
        while (undoStack.size() > maxHistory) {
            ((ArrayDeque<EditAction>) undoStack).removeLast();
        }

        Log.d(TAG, "Recorded: " + action.getDescription()
                + " (undo=" + undoStack.size() + ", redo=0)");
        notifyListener();
    }

    /**
     * Undo the most recent action.
     *
     * @return true if an action was undone, false if nothing to undo
     */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            Log.d(TAG, "Nothing to undo");
            return false;
        }

        EditAction action = undoStack.pop();
        action.undo();
        redoStack.push(action);

        Log.d(TAG, "Undone: " + action.getDescription()
                + " (undo=" + undoStack.size() + ", redo=" + redoStack.size() + ")");
        notifyListener();
        return true;
    }

    /**
     * Redo the most recently undone action.
     *
     * @return true if an action was redone, false if nothing to redo
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            Log.d(TAG, "Nothing to redo");
            return false;
        }

        EditAction action = redoStack.pop();
        action.execute();
        undoStack.push(action);

        Log.d(TAG, "Redone: " + action.getDescription()
                + " (undo=" + undoStack.size() + ", redo=" + redoStack.size() + ")");
        notifyListener();
        return true;
    }

    /**
     * Whether there are actions to undo.
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * Whether there are actions to redo.
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Get the description of the action that would be undone.
     */
    @Nullable
    public String peekUndoDescription() {
        return undoStack.isEmpty() ? null : undoStack.peek().getDescription();
    }

    /**
     * Get the description of the action that would be redone.
     */
    @Nullable
    public String peekRedoDescription() {
        return redoStack.isEmpty() ? null : redoStack.peek().getDescription();
    }

    /**
     * Clear all history.
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        Log.d(TAG, "History cleared");
        notifyListener();
    }

    /**
     * Total number of recorded actions (undo + redo).
     */
    public int size() {
        return undoStack.size() + redoStack.size();
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onUndoRedoStateChanged(canUndo(), canRedo());
        }
    }
}
