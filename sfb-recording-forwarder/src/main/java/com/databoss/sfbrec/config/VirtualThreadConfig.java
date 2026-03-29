package com.databoss.sfbrec.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Virtual Threads tabanli executor konfigurasyonu.
 *
 * <p>JDK 21+ Virtual Threads, her kayit dosyasi icin ayri bir
 * lightweight thread olusturur. 100 paralel dosya islense bile
 * OS thread sayisi minimum kalir.</p>
 *
 * <p>Neden Virtual Threads?</p>
 * <ul>
 *   <li>Kayit dosyasi okuma = blocking I/O → Virtual Thread ideal</li>
 *   <li>STT servisine HTTP gonderim = blocking I/O → Virtual Thread ideal</li>
 *   <li>Platform thread havuzu yonetimi derdi yok</li>
 *   <li>Binlerce concurrent islem, birkaç OS thread ile</li>
 * </ul>
 */
@Slf4j
@Configuration
public class VirtualThreadConfig implements AsyncConfigurer {

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public AsyncTaskExecutor applicationTaskExecutor() {
        log.info("Virtual Threads executor olusturuluyor (JDK {})", Runtime.version());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return new TaskExecutorAdapter(executor);
    }

    @Override
    public AsyncTaskExecutor getAsyncExecutor() {
        return applicationTaskExecutor();
    }
}
