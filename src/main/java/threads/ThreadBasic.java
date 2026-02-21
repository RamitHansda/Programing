package threads;

public class ThreadBasic {
    public static void main(String[] args) {

        Thread anonymousThread= new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Anonymous class thread");
            }
        });

        Thread executeSubclassingMe = new ExecuteSubclassingMe();
        executeSubclassingMe.start();

        anonymousThread.start();

        Thread thread = new Thread(new ExecuteMe());
        thread.start();

//        try{
//        thread.join();
//        anonymousThread.join();
//        } catch (  InterruptedException e){
//          System.out.println("Test exception");
//        }

    }


}

//
class ExecuteMe implements Runnable{
    @Override
    public void run() {
        System.out.println("Test the thunder");
    }
}


//Subclassing
class ExecuteSubclassingMe extends Thread {

    @Override
    public void run() {
        System.out.println("I ran after extending Thread class");
    }

}
