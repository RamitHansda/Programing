package lld.lrucache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheTest {

    private LRUCache<String, Integer> cache;

    @BeforeEach
    void setUp() {
        // Initialize a cache with capacity 3 before each test
        cache = new LRUCache<>(3);
    }

    @Test
    void get_existingKey_returnsValue() {
        // Arrange
        cache.put("one", 1);

        // Act
        Integer value = cache.get("one");

        // Assert
        assertEquals(1, value);
    }

    @Test
    void get_nonExistentKey_returnsNull() {
        // Act
        Integer value = cache.get("missing");

        // Assert
        assertNull(value);
    }

    @Test
    void get_updatesLRUOrder() {
        // Arrange
        cache.put("one", 1);
        cache.put("two", 2);
        cache.put("three", 3);

        // Act - access "one" to make it most recently used
        cache.get("one");

        // Add a new item to evict the least recently used
        cache.put("four", 4);

        // Assert - "two" should be evicted as it's now the LRU
        assertNull(cache.get("two"));
        assertEquals(1, cache.get("one"));
        assertEquals(3, cache.get("three"));
        assertEquals(4, cache.get("four"));
    }

    @Test
    void put_newKey_addsEntry() {
        // Act
        cache.put("one", 1);

        // Assert
        assertEquals(1, cache.get("one"));
    }


    @Test
    void put_existingKey_updatesValue() {
        // Arrange
        cache.put("one", 1);

        // Act
        cache.put("one", 100);

        // Assert
        assertEquals(100, cache.get("one"));
    }

    @Test
    void put_exceedingCapacity_evictsLRU() {
        // Arrange
        cache.put("one", 1);
        cache.put("two", 2);
        cache.put("three", 3);

        // Act - add a new entry exceeding capacity
        cache.put("four", 4);

        // Assert - "one" should be evicted as it was the least recently used
        assertNull(cache.get("one"));
        assertEquals(2, cache.get("two"));
        assertEquals(3, cache.get("three"));
        assertEquals(4, cache.get("four"));
    }

    @Test
    void put_updatesLRUOrder() {
        // Arrange
        cache.put("one", 1);
        cache.put("two", 2);

        // Act - update "one" to make it most recently used
        cache.put("one", 100);
        cache.put("three", 3);

        // Add a new item to evict the least recently used
        cache.put("four", 4);

        // Assert - "two" should be evicted as it's now the LRU
        assertNull(cache.get("two"));
        assertEquals(100, cache.get("one"));
        assertEquals(3, cache.get("three"));
        assertEquals(4, cache.get("four"));
    }

    @Test
    void remove_existingKey_removesEntry() {
        // Arrange
        cache.put("one", 1);
        cache.put("two", 2);

        // Act
        cache.remove("one");

        // Assert
        assertNull(cache.get("one"));
        assertEquals(2, cache.get("two"));
    }

    @Test
    void remove_nonExistentKey_doesNothing() {
        // Arrange
        cache.put("one", 1);

        // Act - try to remove a key that doesn't exist
        cache.remove("missing");

        // Assert - existing entries should remain unchanged
        assertEquals(1, cache.get("one"));
    }

    @Test
    void remove_freesCapacity() {
        // Arrange
        cache.put("one", 1);
        cache.put("two", 2);
        cache.put("three", 3);

        // Act
        cache.remove("one");

        // Now we should have space for one more entry
        cache.put("four", 4);

        // Assert - we should still have all entries except "one"
        assertNull(cache.get("one"));
        assertEquals(2, cache.get("two"));
        assertEquals(3, cache.get("three"));
        assertEquals(4, cache.get("four"));
    }
}