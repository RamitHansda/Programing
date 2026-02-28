package lld.designpatterns.visitor;

import java.util.Map;

/**
 * Visitor: evaluates expression. Variables resolved from context.
 */
public final class EvaluateVisitor implements AstVisitor<Integer> {

    private final Map<String, Integer> variables;

    public EvaluateVisitor(Map<String, Integer> variables) {
        this.variables = variables != null ? Map.copyOf(variables) : Map.of();
    }

    @Override
    public Integer visitLiteral(LiteralNode node) {
        return node.getValue();
    }

    @Override
    public Integer visitBinary(BinaryOpNode node) {
        int l = node.getLeft().accept(this);
        int r = node.getRight().accept(this);
        return switch (node.getOp()) {
            case ADD -> l + r;
            case SUB -> l - r;
            case MUL -> l * r;
            case DIV -> r != 0 ? l / r : 0;
        };
    }

    @Override
    public Integer visitVariable(VariableNode node) {
        return variables.getOrDefault(node.getName(), 0);
    }
}
