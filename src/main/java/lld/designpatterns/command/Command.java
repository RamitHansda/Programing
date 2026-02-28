package lld.designpatterns.command;

/**
 * Command: encapsulate action as object. Supports execute and undo for text editor.
 */
public interface Command {

    void execute();
    void undo();
}
