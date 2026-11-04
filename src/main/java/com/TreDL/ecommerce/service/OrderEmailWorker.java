package com.TreDL.ecommerce.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** SMTP acceptance is not delivery. Ambiguous SMTP completion can cause duplicate messages. */
@Component
public class OrderEmailWorker {
    @Value("${app.mail.worker.enabled:true}") private boolean workerEnabled=true;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> senders;
    private final Environment environment;
    private final boolean enabled;
    private final String from;
    public OrderEmailWorker(JdbcTemplate jdbc,ObjectProvider<JavaMailSender> senders,Environment environment,
            @Value("${app.mail.enabled:false}") boolean enabled,@Value("${app.mail.from:no-reply@3dl.test}") String from){
        this.jdbc=jdbc;this.senders=senders;this.environment=environment;this.enabled=enabled;this.from=from;
    }
    public void scheduled(){if(workerEnabled)try{tick();}catch(Exception failure){/* Durable work remains pending; no sensitive logs. */}}
    public void tick(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("SMTP requires no database transaction");
        if(!enabled)return;
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        for(String id:jdbc.queryForList("SELECT id FROM email_notifications WHERE smtp_accepted_at IS NULL AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY created_at LIMIT 20",String.class,now,now)){
            String token=UUID.randomUUID().toString();
            if(jdbc.update("UPDATE email_notifications SET lease_token=?,lease_until=?,attempts=attempts+1 WHERE id=? AND smtp_accepted_at IS NULL AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)",token,now.plusMinutes(15),id,now,now)==0)continue;
            var row=jdbc.queryForMap("SELECT * FROM email_notifications WHERE id=?",id);
            try{
                String contract=(String)row.get("contract_text");
                // Current documents are drafts. No switch can declare them definitive.
                var profiles=Arrays.asList(environment.getActiveProfiles());
                boolean localOnly=!profiles.isEmpty() && profiles.stream().allMatch(p->p.equals("dev") || p.equals("test"));
                if(contract!=null && !localOnly){
                    retry(id,token,"CONTRACT_REVIEW_REQUIRED",24*60);continue;
                }
                JavaMailSender sender=senders.getIfAvailable();
                if(sender==null)throw new IllegalStateException();
                var message=sender.createMimeMessage();var helper=new MimeMessageHelper(message,true,"UTF-8");
                helper.setFrom(from);helper.setTo((String)row.get("recipient"));helper.setSubject((String)row.get("subject"));
                helper.setText((String)row.get("text_body"),(String)row.get("html_body"));
                if(contract!=null)helper.addAttachment("condizioni-acquisto-prova.txt",new ByteArrayResource(contract.getBytes(StandardCharsets.UTF_8)),"text/plain; charset=UTF-8");
                sender.send(message);
                jdbc.update("UPDATE email_notifications SET smtp_accepted_at=?,last_error=NULL,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",LocalDateTime.now(ZoneOffset.UTC),id,token);
            }catch(Exception failure){
                int attempts=((Number)row.get("attempts")).intValue();
                retry(id,token,"SMTP_OR_PERSISTENCE_FAILURE",Math.min(360,1L<<Math.min(attempts-1,9)));
            }
        }
    }
    private void retry(String id,String token,String reason,long minutes){
        jdbc.update("UPDATE email_notifications SET next_attempt_at=?,last_error=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes),reason,id,token);
    }
}

@Configuration
@ConditionalOnProperty(name="app.mail.worker.enabled",havingValue="true",matchIfMissing=true)
class OrderEmailScheduling {
    // Independent timer: enabling email retries must not enable Stripe reconciliation.
    @org.springframework.context.annotation.Bean(destroyMethod="close")
    EmailTimer orderEmailScheduler(
            OrderEmailWorker worker,@Value("${app.mail.worker.delay-ms:60000}") long delay){
        if(delay<1000)throw new IllegalArgumentException("Email worker delay must be at least one second");
        var scheduler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{
            var thread=new Thread(task,"order-email-worker");thread.setDaemon(true);return thread;
        });
        scheduler.scheduleWithFixedDelay(worker::scheduled,delay,delay,java.util.concurrent.TimeUnit.MILLISECONDS);
        return new EmailTimer(scheduler);
    }
    // Do not expose ScheduledExecutorService: Spring may otherwise select it for Stripe @Scheduled jobs.
    static final class EmailTimer implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService executor;
        EmailTimer(java.util.concurrent.ScheduledExecutorService executor){this.executor=executor;}
        @Override public void close(){executor.shutdown();}
    }
}
