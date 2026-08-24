package lld.taskscheduler;

import java.util.concurrent.TimeUnit;

public class TaskSchedulerRunner {


public static void main(String[] args)
        throws InterruptedException {

    try (TaskScheduler scheduler =
                 new TaskScheduler(4)) {

        String taskId =
                scheduler.schedule(
                        () -> System.out.println(
                                "Hello after 5 seconds"
                        ),
                        5,
                        TimeUnit.SECONDS
                );


        scheduler.scheduleAtFixedRate(
                () -> System.out.println(
                        "Running periodically"
                ),
                2,
                3,
                TimeUnit.SECONDS
        );


        Thread.sleep(15_000);
    }
}}
