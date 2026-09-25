package com.github.highcumontoa.iotdevicetwingatewayjava.config;

import org.springframework.stereotype.Component;

/** 生产环境系统时钟。 */
@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
