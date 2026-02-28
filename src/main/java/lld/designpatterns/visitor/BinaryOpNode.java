package lld.designpatterns.visitor;

import java.util.Objects;

public final class BinaryOpNode implements AstNode {

    public enum Op { ADD, SUB, MUL, DIV }

    private final AstNode left;
    private final Op op;
    private final AstNode right;

    public BinaryOpNode(AstNode left, Op op, AstNode right) {
        this.left = Objects.requireNonNull(left);
        this.op = Objects.requireNonNull(op);
        this.right = Objects.requireNonNull(right);
    }

    public AstNode getLeft() { return left; }
    public Op getOp() { return op; }
    public AstNode getRight() { return right; }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitBinary(this);
    }
}
