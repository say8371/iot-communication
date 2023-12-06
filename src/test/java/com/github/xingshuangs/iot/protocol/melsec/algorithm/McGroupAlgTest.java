package com.github.xingshuangs.iot.protocol.melsec.algorithm;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static org.junit.Assert.assertEquals;


public class McGroupAlgTest {

    @Test
    public void loopExecute() {
        AtomicInteger length = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        McGroupAlg.loopExecute(100, 9, (off, len) -> {
            count.getAndIncrement();
            length.addAndGet(len);
        });
        assertEquals(12, count.get());
        assertEquals(100, length.get());

        length.set(0);
        count.set(0);
        McGroupAlg.loopExecute(120, 7, (off, len) -> {
            count.getAndIncrement();
            length.addAndGet(len);
        });
        assertEquals(18, count.get());
        assertEquals(120, length.get());
    }

    @Test
    public void biLoopExecute() {
        McGroupItem item1 = new McGroupItem(20);
        McGroupItem item2 = new McGroupItem(23);

        AtomicInteger length = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        BiPredicate<McGroupItem, McGroupItem> biPredicate = (i1, i2) -> i1.getLen() + i2.getLen() >= 13;
        McGroupAlg.biLoopExecute(item1, item2, biPredicate, (i1, i2) -> {
            length.addAndGet(i1.getLen());
            length.addAndGet(i2.getLen());
            count.getAndIncrement();
        });
        assertEquals(43, length.get());
        assertEquals(4, count.get());
    }

    @Test
    public void biLoopExecute1() {
        McGroupItem item1 = new McGroupItem(20);
        McGroupItem item2 = new McGroupItem(23);

        AtomicInteger length = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        BiPredicate<McGroupItem, McGroupItem> biPredicate = (i1, i2) -> i1.getLen() * 2 + i2.getLen() >= 14;
        McGroupAlg.biLoopExecute(item1, item2, biPredicate, (i1, i2) -> {
            length.addAndGet(i1.getLen());
            length.addAndGet(i2.getLen());
            count.getAndIncrement();
        });
        assertEquals(43, length.get());
        assertEquals(5, count.get());
    }
}