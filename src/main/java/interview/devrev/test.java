package interview.devrev;

import java.util.Stack;

public class test {
    public static void main(String[] args) {
        System.out.println("ramit");


        // E E E E E E D D D D D D
        // E D E D
        MyQueue queue = new MyQueue();

        queue.enqueue(1);
        queue.enqueue(2);
        queue.enqueue(3);
        System.out.println(queue.dequeue());

        queue.enqueue(4);
        queue.enqueue(5);
        System.out.println(queue.dequeue());
        System.out.println(queue.dequeue());
        System.out.println(queue.dequeue());
        System.out.println(queue.dequeue());
        System.out.println(queue.dequeue());
        System.out.println(queue.dequeue());


        /*
        * queue using stack ds
        * enqueue
        * dequeue
        * stack1 enqueu
        * stack2
        * */
    }

    static class MyQueue{
        Stack<Integer> stack1 = new Stack<>();
        Stack<Integer> stack2 = new Stack<>();
        String lastOperation = "ENQUEUE";

        public void enqueue(int num){
            if(lastOperation.equals( "ENQUEUE")){
                stack1.push(num);
            } else{
                    while(!stack2.empty()){
                        stack1.push(stack2.pop());
                    }
                    stack1.push(num);

                }
//            while(!stack1.empty()){
//                stack2.push(stack1.pop());
//            }
            lastOperation = "ENQUEUE";

        }

        public int dequeue(){
            if(lastOperation.equals( "ENQUEUE")){
                while(!stack1.empty()){
                    stack2.push(stack1.pop());
                }
            }
            lastOperation = "DEQUEUE";
            if(stack2.empty())
                return -1;

//            while(!stack1.empty()){
//                stack2.push(stack1.pop());
//            }
            int top = stack2.pop();

//            while(!stack2.empty()){
//                stack1.push(stack2.pop());
//            }
            return top;
        }
    }
}
