package com.flowops;

import com.flowops.application.request.recovery.DrainIdentityReconciliation;
import com.flowops.worker.IdentityReconciliationWakeupWorker;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityReconciliationWakeupWorkerTest {
    @Test void workflowProcessingRunsOffTheIdentityThreadAndWakeupsCoalesce() throws Exception {
        var drain=mock(DrainIdentityReconciliation.class);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        Thread identityThread=Thread.currentThread();
        doAnswer(call->{
            try {
                assertNotSame(identityThread,Thread.currentThread());started.countDown();
                assertTrue(release.await(5,TimeUnit.SECONDS));
            } finally { finished.countDown(); }
            return null;
        }).when(drain).drain();
        var worker=new IdentityReconciliationWakeupWorker(drain);
        try {
            worker.request();assertTrue(started.await(5,TimeUnit.SECONDS));
            worker.request();worker.request();
            verify(drain,times(1)).drain();
            release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS));
        } finally { release.countDown();worker.destroy(); }
    }
}
