package lld.designpatterns.visitor;

import java.util.Objects;

public final class VariableNode implements AstNode {

    private final String name;

    public VariableNode(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public String getName() { return name; }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitVariable(this);
    }
}
