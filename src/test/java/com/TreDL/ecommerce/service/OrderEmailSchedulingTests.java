package com.TreDL.ecommerce.service;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderEmailSchedulingTests {
    @Test void emailTimerDoesNotEnableGlobalStripeScheduling(){
        var worker=mock(OrderEmailWorker.class);
        new ApplicationContextRunner().withUserConfiguration(OrderEmailScheduling.class)
            .withBean(OrderEmailWorker.class,()->worker)
            .withPropertyValues("app.mail.worker.enabled=true","app.mail.worker.delay-ms=1000")
            .run(context->{
                assertThat(context).hasSingleBean(OrderEmailScheduling.EmailTimer.class);
                assertThat(context).doesNotHaveBean(java.util.concurrent.ScheduledExecutorService.class);
                assertThat(context).doesNotHaveBean(org.springframework.scheduling.TaskScheduler.class);
                assertThat(context).doesNotHaveBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
                verify(worker,timeout(3000)).scheduled();
            });
    }
    @Test void timerCanBeDisabled(){
        new ApplicationContextRunner().withUserConfiguration(OrderEmailScheduling.class)
            .withPropertyValues("app.mail.worker.enabled=false")
            .run(context->assertThat(context).doesNotHaveBean(OrderEmailScheduling.EmailTimer.class));
    }
}
