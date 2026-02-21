package designpatterns.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StrategyPatternDemo {

    @org.junit.jupiter.api.Test
    public void testStrategy() {
        Context context = new Context(new AddStrategy());
        assertEquals(4, context.executeStrategy(1,3));
        context.setMathStrategy(new DivStrategy());
        assertEquals(2, context.executeStrategy(4,2));
        context.setMathStrategy(new MultiplyStrategy());
        assertEquals(15, context.executeStrategy(5,3));
        context.setMathStrategy(new DivStrategy());
        assertEquals(1, context.executeStrategy(5,3));
    }
}
