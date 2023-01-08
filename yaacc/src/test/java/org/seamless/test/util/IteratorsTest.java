package org.seamless.test.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.seamless.util.Iterators;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Christian Bauer
 */
public class IteratorsTest {

    @Test
    public void iterate() throws Exception {
        Foo foo = new Foo();
        int result =
                Executors.newSingleThreadExecutor().submit(new Iterate(foo.getIterator())).get();
        assertEquals(result, 3);
    }

    @Test
    public void concurrentIterate() throws Exception {
        Foo foo = new Foo();

        Iterator<String> it = foo.getIterator();
        Iterator<String> secondIt = foo.getIterator();

        int count = 0;
        while (it.hasNext()) {
            String element = it.next();
            if (count == 0)
                assertEquals(element, "foo");
            if (count == 1) {
                assertEquals(element, "bar");
                // Remove from the underlying collection
                it.remove();

                // This still iterates on the copy
                int result = Executors.newSingleThreadExecutor().submit(new Iterate(secondIt)).get();
                assertEquals(result, 3);
            }
            if (count == 2)
                assertEquals(element, "baz");
            count++;
        }
        assertEquals(count, 3);
        assertEquals(foo.strings.size(), 2);
        assertEquals(foo.strings.get(0), "foo");
        assertEquals(foo.strings.get(1), "baz");
    }

    @Test
    public void concurrentRemove() throws Exception {
        final Foo foo = new Foo();

        int count = 0;
        foo.lock();
        try {
            for (; count < foo.strings.size(); count++) {
                if (count == 2) { // Two for no particular reason,..

                    // Concurrent remove of some element should fail with timeout, preventing deadlock
                    assertTrue(
                            Executors.newSingleThreadExecutor().submit(new Callable<Boolean>() {
                                public Boolean call() throws Exception {
                                    try {
                                        Iterator<String> it = foo.getIterator();
                                        it.next();
                                        it.remove();
                                    } catch (RuntimeException ex) {
                                        if ("Failed to acquire lock".equals(ex.getMessage()))
                                            return true; // All good, we should fail to get the lock
                                        ex.printStackTrace(System.err);
                                    }
                                    return false;
                                }
                            }).get()
                    );
                }
            }
        } finally {
            foo.unlock();
        }

        assertEquals(count, 3);
        assertEquals(foo.strings.size(), 3);
    }

    static class Foo {

        // We need timeouts for the tests, but synchronized is fine
        final protected ReentrantLock lock = new ReentrantLock(true);

        final List<String> strings = new ArrayList<String>();

        Foo() {
            strings.add("foo");
            strings.add("bar");
            strings.add("baz");
        }

        public Iterator<String> getIterator() {
            return new Iterators.Synchronized<String>(strings) {
                @Override
                protected void synchronizedRemove(int index) {
                    lock();
                    try {
                        strings.remove(index);
                    } finally {
                        unlock();
                    }
                }
            };
        }

        protected void lock() {
            try {
                if (!lock.tryLock(500, TimeUnit.MILLISECONDS)) {
                    throw new RuntimeException("Failed to acquire lock");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to acquire lock:" + e);
            }
        }

        protected void unlock() {
            lock.unlock();
        }
    }

    class Iterate implements Callable<Integer> {

        Iterator<String> it;

        Iterate(Iterator<String> it) {
            this.it = it;
        }

        public Integer call() throws Exception {
            int count = 0;
            while (it.hasNext()) {
                String next = it.next();
                if (count == 0)
                    assertEquals(next, "foo");
                if (count == 1)
                    assertEquals(next, "bar");
                if (count == 2)
                    assertEquals(next, "baz");
                count++;
            }
            return count;
        }
    }

}
