package com.github.highcumontoa.iotdevicetwingatewayjava.support;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/** 可控时钟：测试可推进时间以模拟超时等时序。 */
@Component
public class MutableClock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public Instant now() { return now.get(); }

    public void set(Instant instant) { now.set(instant); }

    public void advance(Duration duration) { now.updateAndGet(t -> t.plus(duration)); }
}
