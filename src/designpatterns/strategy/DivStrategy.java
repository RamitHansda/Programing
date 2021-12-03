package designpatterns.strategy;

public class DivStrategy implements MathStrategy{
    @Override
    public int doOperation(int num1, int num2) {
        return num1/num2;
    }
}
