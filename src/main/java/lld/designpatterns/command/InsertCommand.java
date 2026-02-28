package lld.designpatterns.command;

import java.util.Objects;

/**
 * Command: insert text at position. Undo removes the inserted span.
 */
public final class InsertCommand implements Command {

    private final TextEditor editor;
    private final int position;
    private final String text;
    private boolean executed;

    public InsertCommand(TextEditor editor, int position, String text) {
        this.editor = Objects.requireNonNull(editor);
        this.position = position;
        this.text = Objects.requireNonNull(text);
    }

    @Override
    public void execute() {
        editor.insert(position, text);
        executed = true;
    }

    @Override
    public void undo() {
        if (!executed) return;
        editor.delete(position, position + text.length());
        executed = false;
    }
}
