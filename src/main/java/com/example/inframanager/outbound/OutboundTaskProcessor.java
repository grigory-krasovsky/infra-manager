package com.example.inframanager.outbound;

import java.time.Instant;
import java.util.List;

import com.example.inframanager.work.ProcessingStatus;
import com.example.inframanager.work.RetryAfterException;
import com.example.inframanager.work.RetryPolicy;
import com.example.inframanager.work.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Отправляет ровно одну задачу за транзакцию.
 *
 * <p>Блокировка строки удерживается на всё время HTTP-вызова. На наших объёмах это
 * ничего не стоит и именно это не даёт второму воркеру отправить то же сообщение.
 * Доставка — «хотя бы один раз»: падение между успешной отправкой и коммитом приведёт
 * к повторной отправке. Альтернатива — помечать отправленным до отправки — вместо
 * этого теряет сообщения, а для уведомления о деплое это хуже.
 */
@Component
public class OutboundTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboundTaskProcessor.class);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final OutboundTaskRepository repository;
    private final List<OutboundTaskSender> senders;
    private final WorkerProperties.Settings settings;

    public OutboundTaskProcessor(OutboundTaskRepository repository,
                                 List<OutboundTaskSender> senders,
                                 WorkerProperties properties) {
        // По одному отправителю на цель — по той же причине, по какой обработчик один на источник.
        for (OutboundTarget target : OutboundTarget.values()) {
            List<OutboundTaskSender> claiming = senders.stream().filter(s -> s.target() == target).toList();
            if (claiming.size() > 1) {
                throw new IllegalStateException(
                        "More than one OutboundTaskSender claims " + target + ": " + claiming);
            }
        }
        this.repository = repository;
        this.senders = senders;
        this.settings = properties.outbound();
    }

    /** @return true, если этот вызов забрал строку в работу */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(long id) {
        OutboundTask task = repository.lockClaimable(id).orElse(null);
        if (task == null) {
            return false;
        }

        task.setAttempts(task.getAttempts() + 1);

        OutboundTaskSender sender = senders.stream()
                .filter(s -> s.target() == task.getTarget())
                .findFirst()
                .orElse(null);

        if (sender == null) {
            log.warn("No sender for {} task {} ({}); skipping",
                    task.getTarget(), task.getId(), task.getAction());
            task.setStatus(ProcessingStatus.SKIPPED);
            task.setLastError("no sender registered for target " + task.getTarget());
            task.setCompletedAt(Instant.now());
            return true;
        }

        try {
            sender.send(task);
            task.setStatus(ProcessingStatus.DONE);
            task.setLastError(null);
            task.setCompletedAt(Instant.now());
        } catch (RetryAfterException e) {
            // Не считается настоящей попыткой: API вообще отказался смотреть на запрос,
            // и потратить на это бюджет повторов — значит потерять корректные сообщения.
            task.setAttempts(task.getAttempts() - 1);
            task.setLastError(String.valueOf(e));
            task.setNextAttemptAt(Instant.now().plus(e.getRetryAfter()));
            log.warn("{} rate limited task {}; retrying in {}",
                    task.getTarget(), task.getId(), e.getRetryAfter());
        } catch (Exception e) {
            recordFailure(task, e);
        }
        return true;
    }

    private void recordFailure(OutboundTask task, Exception e) {
        String message = String.valueOf(e);
        task.setLastError(message.length() > MAX_ERROR_LENGTH
                ? message.substring(0, MAX_ERROR_LENGTH)
                : message);

        if (task.getAttempts() >= settings.maxAttempts()) {
            log.error("Giving up on {} task {} after {} attempts",
                    task.getTarget(), task.getId(), task.getAttempts(), e);
            task.setStatus(ProcessingStatus.FAILED);
            task.setCompletedAt(Instant.now());
        } else {
            Instant next = RetryPolicy.nextAttemptAt(
                    Instant.now(), task.getAttempts(), settings.baseBackoff(), settings.maxBackoff());
            log.warn("Attempt {} failed for {} task {}; retrying at {}",
                    task.getAttempts(), task.getTarget(), task.getId(), next, e);
            task.setNextAttemptAt(next);
        }
    }
}
