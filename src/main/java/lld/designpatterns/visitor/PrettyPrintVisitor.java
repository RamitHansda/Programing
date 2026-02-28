package lld.designpatterns.visitor;

public final class PrettyPrintVisitor implements AstVisitor<String> {

    @Override
    public String visitLiteral(LiteralNode node) {
        return String.valueOf(node.getValue());
    }

    @Override
    public String visitBinary(BinaryOpNode node) {
        String left = node.getLeft().accept(this);
        String right = node.getRight().accept(this);
        String op = node.getOp().name().toLowerCase();
        return "(" + left + " " + op + " " + right + ")";
    }

    @Override
    public String visitVariable(VariableNode node) {
        return node.getName();
    }
}
