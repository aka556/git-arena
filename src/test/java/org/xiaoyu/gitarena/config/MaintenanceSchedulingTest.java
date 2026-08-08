package org.xiaoyu.gitarena.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住"作业真的被调度器登记了"这件事（database.md §9）。
 *
 * <p>回收/刷新作业的全部价值在于按时自动跑；若 @EnableScheduling 因结构调整而失活，
 * 业务测试仍会全绿（它们直接调 service），但线上会静默退化成磁盘只涨不回收。
 */
@SpringBootTest
class MaintenanceSchedulingTest {

    @Autowired
    private ScheduledAnnotationBeanPostProcessor postProcessor;

    @Test
    void all_three_registered_jobs_are_actually_scheduled() {
        Set<ScheduledTask> tasks = postProcessor.getScheduledTasks();
        assertThat(tasks).as("§9 登记的三件作业须真的被调度器接管").hasSizeGreaterThanOrEqualTo(3);
        assertThat(tasks).extracting(task -> task.getTask().toString())
                .anySatisfy(desc -> assertThat(desc).contains("refreshLeaderboards"))
                .anySatisfy(desc -> assertThat(desc).contains("cleanupGuests"))
                .anySatisfy(desc -> assertThat(desc).contains("reclaimSandboxes"));
    }
}
