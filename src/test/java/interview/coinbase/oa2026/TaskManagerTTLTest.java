package interview.coinbase.oa2026;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerTTLTest {

    @Test
    void createListTopByPriority() {
        TaskManagerTTL tm = new TaskManagerTTL();
        tm.createTask("t1", "pay invoice", 5);
        tm.createTask("t2", "pay rent", 10);
        tm.createTask("t3", "review PR", 10);

        List<TaskManagerTTL.Task> top = tm.topTasks(2);
        // priority 10 first; tie-break by id: t2 before t3
        assertEquals("t2", top.get(0).id);
        assertEquals("t3", top.get(1).id);

        List<TaskManagerTTL.Task> pay = tm.topTasksByNameContains(5, "pay");
        assertEquals(2, pay.size());
    }

    @Test
    void assignRespectsQuotaAndTTL() {
        TaskManagerTTL tm = new TaskManagerTTL();
        tm.createTask("t1", "task1", 1);
        tm.createTask("t2", "task2", 1);
        tm.addUser("u1", 1);

        assertNotNull(tm.assign(0, "u1", "t1", 10)); // active [0,10)
        assertNull(tm.assign(5, "u1", "t2", 10));    // quota full

        // after expiry at 10, can assign again
        assertNotNull(tm.assign(10, "u1", "t2", 5));
        assertEquals(1, tm.listActive("u1", 10).size());
        assertEquals(1, tm.listExpired("u1", 10).size());
    }

    @Test
    void cannotCompleteExpired() {
        TaskManagerTTL tm = new TaskManagerTTL();
        tm.createTask("t1", "task1", 1);
        tm.addUser("u1", 5);
        tm.assign(0, "u1", "t1", 5); // expires at 5

        assertFalse(tm.complete("u1", "t1", 5)); // expired
        assertTrue(tm.listExpired("u1", 5).stream().anyMatch(a -> a.taskId.equals("t1")));
    }

    @Test
    void completeEarliestOfMultipleAssignments() {
        TaskManagerTTL tm = new TaskManagerTTL();
        tm.createTask("t1", "task1", 1);
        tm.addUser("u1", 5);
        String a1 = tm.assign(0, "u1", "t1", 100);
        String a2 = tm.assign(10, "u1", "t1", 100);

        assertTrue(tm.complete("u1", "t1", 20));
        // earliest (a1) completed; a2 still active
        assertEquals(1, tm.listActive("u1", 20).size());
        assertEquals(a2, tm.listActive("u1", 20).get(0).id);
        assertNotNull(a1);
    }

    @Test
    void expirationNotificationFiredOnCleanup() {
        TaskManagerTTL tm = new TaskManagerTTL();
        AtomicInteger notified = new AtomicInteger();
        List<String> expiredIds = new ArrayList<>();
        tm.onExpiration(a -> {
            notified.incrementAndGet();
            expiredIds.add(a.id);
        });

        tm.createTask("t1", "task1", 1);
        tm.addUser("u1", 5);
        String id = tm.assign(0, "u1", "t1", 3); // expire at 3
        tm.cleanupExpired(3);

        assertEquals(1, notified.get());
        assertEquals(List.of(id), expiredIds);
    }

    @Test
    void halfOpenIntervalActiveUntilExpireExclusive() {
        TaskManagerTTL tm = new TaskManagerTTL();
        tm.createTask("t1", "task1", 1);
        tm.addUser("u1", 5);
        tm.assign(10, "u1", "t1", 5); // [10, 15)

        assertEquals(0, tm.listActive("u1", 9).size());
        assertEquals(1, tm.listActive("u1", 10).size());
        assertEquals(1, tm.listActive("u1", 14).size());
        assertEquals(0, tm.listActive("u1", 15).size());
    }
}
