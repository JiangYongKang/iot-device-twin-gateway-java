package com.github.highcumontoa.iotdevicetwingatewayjava.config;

/** 可控时序时钟，测试中可替换为手动推进实现。 */
public interface TimeSource {
    long now();
}
