package com.jitong.im.audit;

public interface SecurityAuditSink {

    void record(SecurityAuditEvent event);
}
