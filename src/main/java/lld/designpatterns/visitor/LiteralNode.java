package lld.designpatterns.visitor;

import java.util.Objects;

public final class LiteralNode implements AstNode {

    private final int value;

    public LiteralNode(int value) {
        this.value = value;
    }

    public int getValue() { return value; }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitLiteral(this);
    }
}
