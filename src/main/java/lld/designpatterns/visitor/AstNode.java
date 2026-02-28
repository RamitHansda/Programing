package lld.designpatterns.visitor;

/**
 * AST node for a simple expression language. Visitor adds operations (pretty-print, evaluate, type-check) without changing node classes.
 */
public interface AstNode {

    <R> R accept(AstVisitor<R> visitor);
}
