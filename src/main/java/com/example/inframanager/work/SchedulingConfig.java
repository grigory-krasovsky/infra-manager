package com.example.inframanager.work;

import com.example.inframanager.event.InboundEventWorker;
import com.example.inframanager.outbound.OutboundTaskWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Регистрирует два воркера программно, а не через {@code @Scheduled}: интервалы
 * берутся напрямую из {@link WorkerProperties}, а тесты могут полностью выключить
 * опрос вместо того, чтобы состязаться с ним.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final WorkerProperties properties;
    private final InboundEventWorker inboundWorker;
    private final OutboundTaskWorker outboundWorker;

    public SchedulingConfig(WorkerProperties properties,
                            InboundEventWorker inboundWorker,
                            OutboundTaskWorker outboundWorker) {
        this.properties = properties;
        this.inboundWorker = inboundWorker;
        this.outboundWorker = outboundWorker;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.schedulingEnabled()) {
            log.info("Worker scheduling disabled; workers must be driven manually");
            return;
        }
        registrar.addFixedDelayTask(inboundWorker::runOnce, properties.inbound().pollInterval());
        registrar.addFixedDelayTask(outboundWorker::runOnce, properties.outbound().pollInterval());
        log.info("Workers scheduled: inbound every {}, outbound every {}",
                properties.inbound().pollInterval(), properties.outbound().pollInterval());
    }
}
