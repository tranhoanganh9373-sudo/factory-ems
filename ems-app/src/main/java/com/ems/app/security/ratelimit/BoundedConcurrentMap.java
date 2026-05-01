package com.ems.app.security.ratelimit;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * ConcurrentMap-shaped wrapper around an access-order LRU {@link LinkedHashMap},
 * capped at {@code maxEntries} via {@code removeEldestEntry}.
 *
 * <p>Single intrinsic lock on the backing map. Sufficient for low-contention lookup
 * + creation in {@link RateLimitFilter}; per-IP rate enforcement remains lock-free
 * inside Bucket4j once a bucket reference is obtained.
 *
 * <p><b>Thread-safety contract:</b> single-method calls (get, put, putIfAbsent,
 * compute*) are atomic. External compound operations (e.g., manual get-then-put)
 * must use the provided atomic methods or hold the lock on the backing map.
 *
 * <p><b>Iteration contract:</b> {@link #entrySet()} returns the backing
 * {@link Collections#synchronizedMap synchronized} map's entrySet. Per JDK
 * Javadoc, callers MUST manually {@code synchronized (this)} around iteration
 * to avoid {@link java.util.ConcurrentModificationException}. The default
 * methods inherited from {@link AbstractMap} (containsValue, equals, hashCode)
 * iterate entrySet and are NOT thread-safe — do not call them under contention.
 * RateLimitFilter only uses {@link #computeIfAbsent} so this constraint is moot
 * in production; documented here for any future caller.
 */
final class BoundedConcurrentMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

    private final Map<K, V> backing;

    BoundedConcurrentMap(int maxEntries) {
        this.backing = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return backing.entrySet();
    }

    @Override
    public V put(K key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V get(Object key) {
        return backing.get(key);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        synchronized (backing) {
            V existing = backing.get(key);
            if (existing != null) {
                return existing;
            }
            backing.put(key, value);
            return null;
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        synchronized (backing) {
            V existing = backing.get(key);
            if (existing != null && existing.equals(value)) {
                backing.remove(key);
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        synchronized (backing) {
            V existing = backing.get(key);
            if (existing != null && existing.equals(oldValue)) {
                backing.put(key, newValue);
                return true;
            }
            return false;
        }
    }

    @Override
    public V replace(K key, V value) {
        synchronized (backing) {
            if (backing.containsKey(key)) {
                return backing.put(key, value);
            }
            return null;
        }
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        synchronized (backing) {
            V existing = backing.get(key);
            if (existing != null) {
                return existing;
            }
            V created = mappingFunction.apply(key);
            if (created != null) {
                backing.put(key, created);
            }
            return created;
        }
    }
}
