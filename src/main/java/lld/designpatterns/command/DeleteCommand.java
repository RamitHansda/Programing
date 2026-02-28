package lld.designpatterns.command;

import java.util.Objects;

/**
 * Command: delete range. Undo re-inserts the deleted text.
 */
public final class DeleteCommand implements Command {

    private final TextEditor editor;
    private final int start;
    private final int end;
    private String deleted;
    private boolean executed;

    public DeleteCommand(TextEditor editor, int start, int end) {
        this.editor = Objects.requireNonNull(editor);
        this.start = start;
        this.end = end;
    }

    @Override
    public void execute() {
        deleted = editor.getContent().subSequence(start, end).toString();
        editor.delete(start, end);
        executed = true;
    }

    @Override
    public void undo() {
        if (!executed || deleted == null) return;
        editor.insert(start, deleted);
        executed = false;
    }
}
