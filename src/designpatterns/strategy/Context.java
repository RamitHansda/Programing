package designpatterns.strategy;

public class Context {
    private MathStrategy mathStrategy;

    public Context(MathStrategy mathStrategy) {
        this.mathStrategy = mathStrategy;
    }

    public int executeStrategy(int num1, int num2){
        return mathStrategy.doOperation(num1, num2);
    }

    public void setMathStrategy(MathStrategy mathStrategy) {
        this.mathStrategy = mathStrategy;
    }
}
