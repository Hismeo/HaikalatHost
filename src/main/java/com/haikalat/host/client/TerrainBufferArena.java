package com.haikalat.host.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL45C;

import java.util.TreeMap;
import java.util.TreeSet;

/** Render-thread GPU replicas with stable element offsets until their source is uploaded again. */
final class TerrainBufferArena {
    static final int INVALID_SOURCE = -1;
    static final int CAPACITY = -2;
    static final int FRAGMENTATION = -3;
    private final Int2ObjectOpenHashMap<Slice> resident = new Int2ObjectOpenHashMap<>();
    private final TreeMap<Integer, Integer> freeByOffset = new TreeMap<>();
    // A size-ordered lookup avoids scanning and allocating Map.Entry snapshots on every miss.
    private final TreeSet<Long> freeBySize = new TreeSet<>();
    private final int elementBytes;
    private final int minCapacity;
    private final int maxCapacity;
    private int buffer;
    private long storageVersion;
    private int capacity;
    private int end;
    private int used;

    TerrainBufferArena(int elementBytes, int minCapacity, int maxCapacity) {
        this.elementBytes = elementBytes;
        this.minCapacity = minCapacity;
        this.maxCapacity = maxCapacity;
    }

    int buffer() {
        return buffer;
    }

    long storageVersion() {
        return storageVersion;
    }

    int size() {
        return resident.size();
    }

    int copy(int source, int count, long epoch) {
        Slice entry = resident.get(source);
        if (entry != null) {
            entry.lastUsed = epoch;
            return entry.count == count ? entry.first : INVALID_SOURCE;
        }
        if (count <= 0 || count > maxCapacity) {
            return count > maxCapacity ? CAPACITY : INVALID_SOURCE;
        }
        if (count > maxCapacity - used) {
            return CAPACITY;
        }
        Long span = freeBySize.ceiling(spanKey(0, count));
        if (span == null && count > maxCapacity - end) {
            return FRAGMENTATION;
        }
        if (GL45C.glGetNamedBufferParameteri(source, GL15C.GL_BUFFER_SIZE) != count * elementBytes) {
            return INVALID_SOURCE;
        }
        int first;
        if (span == null) {
            first = end;
            int required = first + count;
            if (required > capacity) {
                grow(required);
            }
            end = required;
        } else {
            first = span.intValue();
            int available = (int) (span >>> Integer.SIZE);
            removeSpan(first, available);
            if (available > count) {
                addSpan(first + count, available - count);
            }
        }
        GL45C.glCopyNamedBufferSubData(source, buffer, 0L, (long) first * elementBytes, (long) count * elementBytes);
        resident.put(source, new Slice(first, count, epoch));
        used += count;
        return first;
    }

    void touch(int source, long epoch) {
        Slice slice = resident.get(source);
        if (slice != null) {
            slice.lastUsed = epoch;
        }
    }

    int evictBefore(long epoch) {
        int evicted = 0;
        var entries = resident.int2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            Slice slice = entries.next().getValue();
            if (slice.lastUsed < epoch) {
                entries.remove();
                release(slice);
                evicted++;
            }
        }
        return evicted;
    }

    private void grow(int required) {
        int nextCapacity = Math.min(maxCapacity, Math.max(minCapacity, Integer.highestOneBit(required - 1) << 1));
        int nextBuffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(nextBuffer, (long) nextCapacity * elementBytes, GL15C.GL_STATIC_DRAW);
        if (buffer != 0) {
            if (end > 0) {
                GL45C.glCopyNamedBufferSubData(buffer, nextBuffer, 0L, 0L, (long) end * elementBytes);
            }
            GL15C.glDeleteBuffers(buffer);
        }
        buffer = nextBuffer;
        storageVersion++;
        capacity = nextCapacity;
    }

    boolean invalidate(int source) {
        Slice released = resident.remove(source);
        if (released == null) {
            return false;
        }
        release(released);
        return true;
    }

    private void release(Slice released) {
        used -= released.count;
        int first = released.first;
        int last = first + released.count;
        var previous = freeByOffset.lowerEntry(first);
        if (previous != null && previous.getKey() + previous.getValue() == first) {
            first = previous.getKey();
            removeSpan(first, previous.getValue());
        }
        Integer next = freeByOffset.get(last);
        if (next != null) {
            removeSpan(last, next);
            last += next;
        }
        if (last == end) {
            end = first;
        } else {
            addSpan(first, last - first);
        }
    }

    private static long spanKey(int first, int count) {
        return ((long) count << Integer.SIZE) | Integer.toUnsignedLong(first);
    }

    private void addSpan(int first, int count) {
        freeByOffset.put(first, count);
        freeBySize.add(spanKey(first, count));
    }

    private void removeSpan(int first, int count) {
        freeByOffset.remove(first);
        freeBySize.remove(spanKey(first, count));
    }

    void clear() {
        if (buffer != 0) {
            GL15C.glDeleteBuffers(buffer);
            buffer = 0;
            storageVersion++;
        }
        resident.clear();
        freeByOffset.clear();
        freeBySize.clear();
        capacity = 0;
        end = 0;
        used = 0;
    }

    private static final class Slice {
        final int first;
        final int count;
        long lastUsed;

        Slice(int first, int count, long lastUsed) {
            this.first = first;
            this.count = count;
            this.lastUsed = lastUsed;
        }
    }
}
