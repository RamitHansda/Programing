package interview.coinbase.oa2026;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lodely / Coinbase OA 2026 — Task Management System with TTL
 *
 * Tasks expire after N seconds (half-open interval [start, start+ttl)).
 * Supports create/complete/delete, assignment with per-user quota,
 * priority listing, and expiration notifications.
 *
 * Active window: [startTime, startTime + ttl)
 * Expired at:    startTime + ttl
 */
public class TaskManagerTTL {

    private final Map<String, Task> tasks = new HashMap<>();
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, List<Assignment>> byUser = new HashMap<>();

    /** Min-heap of assignments by expireAt for efficient cleanup. */
    private final PriorityQueue<Assignment> expiryHeap =
            new PriorityQueue<>(Comparator.comparingLong(a -> a.expireAt));

    private final List<Consumer<Assignment>> expirationListeners = new CopyOnWriteArrayList<>();
    private long assignmentSeq = 0;

    // ---- Task CRUD ----

    public boolean createTask(String taskId, String name, int priority) {
        if (tasks.containsKey(taskId)) {
            return false;
        }
        tasks.put(taskId, new Task(taskId, name, priority));
        return true;
    }

    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }

    public boolean updateTask(String taskId, String name, Integer priority) {
        Task t = tasks.get(taskId);
        if (t == null) {
            return false;
        }
        if (name != null) {
            t.name = name;
        }
        if (priority != null) {
            t.priority = priority;
        }
        return true;
    }

    public boolean deleteTask(String taskId) {
        if (!tasks.containsKey(taskId)) {
            return false;
        }
        tasks.remove(taskId);
        return true;
    }

    // ---- Listing ----

    /** Top N tasks by priority desc, then taskId asc. */
    public List<Task> topTasks(int n) {
        return topTasksFiltered(n, null);
    }

    /** Top N tasks whose name contains substring (case-sensitive). */
    public List<Task> topTasksByNameContains(int n, String substring) {
        return topTasksFiltered(n, substring);
    }

    private List<Task> topTasksFiltered(int n, String substring) {
        List<Task> list = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (substring == null || t.name.contains(substring)) {
                list.add(t);
            }
        }
        list.sort((a, b) -> {
            int c = Integer.compare(b.priority, a.priority);
            return c != 0 ? c : a.id.compareTo(b.id);
        });
        if (n < list.size()) {
            return new ArrayList<>(list.subList(0, Math.max(0, n)));
        }
        return list;
    }

    // ---- Users + assignment ----

    public boolean addUser(String userId, int quota) {
        if (users.containsKey(userId) || quota < 0) {
            return false;
        }
        users.put(userId, new User(userId, quota));
        byUser.put(userId, new ArrayList<>());
        return true;
    }

    /**
     * Assign task to user at startTime with ttl seconds.
     * Fails if user/task missing, ttl &lt;= 0, or active quota would be exceeded.
     * Returns assignment id, or null on failure.
     */
    public String assign(long startTime, String userId, String taskId, long ttl) {
        cleanupExpired(startTime);
        if (!users.containsKey(userId) || !tasks.containsKey(taskId) || ttl <= 0) {
            return null;
        }
        User user = users.get(userId);
        int active = countActive(userId, startTime);
        if (active >= user.quota) {
            return null;
        }
        String id = "a" + (++assignmentSeq);
        Assignment a = new Assignment(id, userId, taskId, startTime, startTime + ttl);
        byUser.get(userId).add(a);
        expiryHeap.offer(a);
        return id;
    }

    /** Active assignments for user at time t (status ACTIVE, not completed). */
    public List<Assignment> listActive(String userId, long t) {
        cleanupExpired(t);
        List<Assignment> result = new ArrayList<>();
        List<Assignment> all = byUser.get(userId);
        if (all == null) {
            return result;
        }
        for (Assignment a : all) {
            if (a.status == Status.ACTIVE && a.isActiveAt(t)) {
                result.add(a);
            }
        }
        return result;
    }

    /** Expired (not completed) assignments for user at time t. */
    public List<Assignment> listExpired(String userId, long t) {
        cleanupExpired(t);
        List<Assignment> result = new ArrayList<>();
        List<Assignment> all = byUser.get(userId);
        if (all == null) {
            return result;
        }
        for (Assignment a : all) {
            if (a.status == Status.EXPIRED || (a.status == Status.ACTIVE && a.expireAt <= t)) {
                // ACTIVE+past expire should already be EXPIRED via cleanup; include EXPIRED
                if (a.status == Status.EXPIRED) {
                    result.add(a);
                }
            }
        }
        return result;
    }

    /**
     * Complete taskId for user at time t.
     * Cannot complete expired. If multiple active (userId, taskId), completes earliest startTime.
     */
    public boolean complete(String userId, String taskId, long t) {
        cleanupExpired(t);
        List<Assignment> all = byUser.get(userId);
        if (all == null) {
            return false;
        }
        Assignment best = null;
        for (Assignment a : all) {
            if (a.taskId.equals(taskId) && a.status == Status.ACTIVE && a.isActiveAt(t)) {
                if (best == null || a.startTime < best.startTime
                        || (a.startTime == best.startTime && a.id.compareTo(best.id) < 0)) {
                    best = a;
                }
            }
        }
        if (best == null) {
            return false;
        }
        best.status = Status.COMPLETED;
        best.completedAt = t;
        return true;
    }

    public void onExpiration(Consumer<Assignment> listener) {
        expirationListeners.add(listener);
    }

    /**
     * Lazily expire everything with expireAt &lt;= now.
     * Heap gives O(k log n) for k newly expired items.
     */
    public void cleanupExpired(long now) {
        while (!expiryHeap.isEmpty() && expiryHeap.peek().expireAt <= now) {
            Assignment a = expiryHeap.poll();
            if (a.status == Status.ACTIVE) {
                a.status = Status.EXPIRED;
                for (Consumer<Assignment> listener : expirationListeners) {
                    listener.accept(a);
                }
            }
        }
    }

    private int countActive(String userId, long t) {
        int count = 0;
        for (Assignment a : byUser.get(userId)) {
            if (a.status == Status.ACTIVE && a.isActiveAt(t)) {
                count++;
            }
        }
        return count;
    }

    // ---- Models ----

    public enum Status { ACTIVE, COMPLETED, EXPIRED, DELETED }

    public static final class Task {
        public final String id;
        public String name;
        public int priority;

        Task(String id, String name, int priority) {
            this.id = id;
            this.name = name;
            this.priority = priority;
        }
    }

    public static final class User {
        public final String id;
        public final int quota;

        User(String id, int quota) {
            this.id = id;
            this.quota = quota;
        }
    }

    public static final class Assignment {
        public final String id;
        public final String userId;
        public final String taskId;
        public final long startTime;
        public final long expireAt;
        public Status status = Status.ACTIVE;
        public Long completedAt;

        Assignment(String id, String userId, String taskId, long startTime, long expireAt) {
            this.id = id;
            this.userId = userId;
            this.taskId = taskId;
            this.startTime = startTime;
            this.expireAt = expireAt;
        }

        /** Half-open [startTime, expireAt). */
        public boolean isActiveAt(long t) {
            return t >= startTime && t < expireAt;
        }
    }
}
