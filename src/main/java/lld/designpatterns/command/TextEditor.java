package lld.designpatterns.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Text editor with undo/redo. Executes commands and keeps history.
 */
public final class TextEditor {

    private final StringBuilder buffer = new StringBuilder();
    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    public void execute(Command command) {
        Objects.requireNonNull(command);
        command.execute();
        undoStack.push(command);
        redoStack.clear();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        Command cmd = undoStack.pop();
        cmd.undo();
        redoStack.push(cmd);
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        Command cmd = redoStack.pop();
        cmd.execute();
        undoStack.push(cmd);
    }

    public void insert(int position, String text) {
        buffer.insert(position, text);
    }

    public void delete(int start, int end) {
        buffer.delete(start, end);
    }

    public String getText() {
        return buffer.toString();
    }

    public int length() {
        return buffer.length();
    }

    /** Returns a snapshot of current content for command use. */
    public CharSequence getContent() {
        return buffer;
    }
}
