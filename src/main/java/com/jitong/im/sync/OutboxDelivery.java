package com.jitong.im.sync;

public interface OutboxDelivery {

    boolean deliver(OutboxRecord record);
}
