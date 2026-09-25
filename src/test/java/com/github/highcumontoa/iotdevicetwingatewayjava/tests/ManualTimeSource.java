package com.github.highcumontoa.iotdevicetwingatewayjava.tests;

import com.github.highcumontoa.iotdevicetwingatewayjava.config.TimeSource;

/** 测试用手动推进时钟，保证时序可控。 */
public class ManualTimeSource implements TimeSource {
    private long now;

    public ManualTimeSource(long start) {
        this.now = start;
    }

    @Override
    public long now() {
        return now;
    }

    public void advance(long millis) {
        now += millis;
    }
}
