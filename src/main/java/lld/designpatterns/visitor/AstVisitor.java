package lld.designpatterns.visitor;

/**
 * Visitor: one method per node type. New operations = new visitor implementation.
 */
public interface AstVisitor<R> {

    R visitLiteral(LiteralNode node);
    R visitBinary(BinaryOpNode node);
    R visitVariable(VariableNode node);
}
